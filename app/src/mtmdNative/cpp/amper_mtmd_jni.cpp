#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>
#include <limits>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"
#include "mtmd.h"
#include "mtmd-helper.h"

namespace {

constexpr const char * TAG = "AMPER-MTMD";
constexpr const char * CANCELLED = "AMPER_MTMD_CANCELLED";
constexpr jint KIND_IMAGE = 1;
constexpr jint KIND_AUDIO = 2;
constexpr jint CAP_IMAGE = 1;
constexpr jint CAP_AUDIO = 2;
constexpr size_t MIB = 1024U * 1024U;
constexpr jsize NATIVE_METRIC_COUNT = 4;
constexpr jsize METRIC_PROMPT_TOKENS = 0;
constexpr jsize METRIC_PROMPT_EVAL_MS = 1;
constexpr jsize METRIC_GENERATION_MS = 2;
constexpr jsize METRIC_OUTPUT_TOKENS = 3;

struct AccelerationProfile {
    bool use_gpu = false;
    int32_t gpu_layers = 0;
    std::string identity = "cpu";
};

std::once_flag g_backend_once;
std::mutex g_requests_mutex;
std::unordered_map<jlong, std::shared_ptr<std::atomic_bool>> g_requests;

struct WarmSession {
    llama_model * model = nullptr;
    mtmd_context * mm = nullptr;
    jint threads = 1;
    int32_t gpu_layers = 0;
    bool use_gpu = false;

    ~WarmSession() {
        if (mm != nullptr) mtmd_free(mm);
        if (model != nullptr) llama_model_free(model);
    }

    WarmSession() = default;
    WarmSession(const WarmSession &) = delete;
    WarmSession & operator=(const WarmSession &) = delete;
};

std::mutex g_warm_residency_mutex;
std::unordered_map<std::string, std::shared_ptr<WarmSession>> g_warm_sessions;


struct WarmTextSession {
    llama_model * model = nullptr;
    jint threads = 1;
    int32_t gpu_layers = 0;
    bool use_gpu = false;

    ~WarmTextSession() {
        if (model != nullptr) llama_model_free(model);
    }

    WarmTextSession() = default;
    WarmTextSession(const WarmTextSession &) = delete;
    WarmTextSession & operator=(const WarmTextSession &) = delete;
};

std::unordered_map<std::string, std::shared_ptr<WarmTextSession>> g_warm_text_sessions;

void init_backend_once() {
    std::call_once(g_backend_once, [] {
        llama_backend_init();
    });
}

AccelerationProfile acceleration_profile() {
#if defined(AMPER_MTMD_VULKAN)
    init_backend_once();
    const size_t count = ggml_backend_dev_count();
    for (size_t i = 0; i < count; ++i) {
        ggml_backend_dev_t device = ggml_backend_dev_get(i);
        if (device == nullptr) continue;
        const auto type = ggml_backend_dev_type(device);
        if (
            type != GGML_BACKEND_DEVICE_TYPE_GPU &&
            type != GGML_BACKEND_DEVICE_TYPE_IGPU
        ) {
            continue;
        }

        size_t free_bytes = 0;
        size_t total_bytes = 0;
        ggml_backend_dev_memory(device, &free_bytes, &total_bytes);

        // Keep Vulkan offload deliberately bounded on mobile. This is an execution accelerator,
        // not permission to consume all shared device memory. A zero-layer plan stays on CPU.
        const size_t free_mib = free_bytes / MIB;
        int32_t layers = 0;
        if (free_mib >= 6144U) {
            layers = 16;
        } else if (free_mib >= 4096U) {
            layers = 12;
        } else if (free_mib >= 2048U) {
            layers = 8;
        } else if (free_mib >= 1024U) {
            layers = 4;
        }

        if (layers <= 0) continue;

        const char * name = ggml_backend_dev_name(device);
        std::ostringstream identity;
        identity << "vulkan:"
                 << (name != nullptr ? name : "gpu")
                 << ":layers=" << layers;
        return AccelerationProfile {
            true,
            layers,
            identity.str()
        };
    }
#endif
    return AccelerationProfile {};
}

std::shared_ptr<std::atomic_bool> create_request(jlong id) {
    auto flag = std::make_shared<std::atomic_bool>(false);
    std::lock_guard<std::mutex> lock(g_requests_mutex);
    g_requests[id] = flag;
    return flag;
}

std::shared_ptr<std::atomic_bool> find_request(jlong id) {
    std::lock_guard<std::mutex> lock(g_requests_mutex);
    auto it = g_requests.find(id);
    return it == g_requests.end() ? nullptr : it->second;
}

void erase_request(jlong id) {
    std::lock_guard<std::mutex> lock(g_requests_mutex);
    g_requests.erase(id);
}

bool model_load_progress_callback(float progress, void * data);

std::shared_ptr<WarmSession> get_or_load_warm_session(
    const std::string & key,
    const std::string & model_path,
    const std::string & projector_path,
    jint threads,
    std::atomic_bool * cancellation
) {
    const AccelerationProfile acceleration = acceleration_profile();
    {
        std::lock_guard<std::mutex> lock(g_warm_residency_mutex);
        auto it = g_warm_sessions.find(key);
        if (it != g_warm_sessions.end()) {
            if (
                it->second->threads != threads ||
                it->second->gpu_layers != acceleration.gpu_layers ||
                it->second->use_gpu != acceleration.use_gpu
            ) {
                return nullptr;
            }
            return it->second;
        }

        // Drop the previous resident model before allocating the replacement so switching
        // modalities cannot transiently require two full warm GGUF model states on mobile.
        g_warm_sessions.clear();
        g_warm_text_sessions.clear();
    }

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = acceleration.gpu_layers;
    model_params.progress_callback = model_load_progress_callback;
    model_params.progress_callback_user_data = cancellation;
    llama_model * raw_model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (raw_model == nullptr) return nullptr;
    if (cancellation != nullptr && cancellation->load(std::memory_order_relaxed)) {
        llama_model_free(raw_model);
        return nullptr;
    }

    mtmd_context_params mm_params = mtmd_context_params_default();
    mm_params.use_gpu = acceleration.use_gpu;
    mm_params.print_timings = false;
    mm_params.n_threads = threads;
    mm_params.warmup = false;
    mm_params.progress_callback = model_load_progress_callback;
    mm_params.progress_callback_user_data = cancellation;

    if (cancellation != nullptr && cancellation->load(std::memory_order_relaxed)) {
        llama_model_free(raw_model);
        return nullptr;
    }
    mtmd_context * raw_mm = mtmd_init_from_file(projector_path.c_str(), raw_model, mm_params);
    if (raw_mm == nullptr) {
        llama_model_free(raw_model);
        return nullptr;
    }
    if (cancellation != nullptr && cancellation->load(std::memory_order_relaxed)) {
        mtmd_free(raw_mm);
        llama_model_free(raw_model);
        return nullptr;
    }

    auto created = std::make_shared<WarmSession>();
    created->model = raw_model;
    created->mm = raw_mm;
    created->threads = threads;
    created->gpu_layers = acceleration.gpu_layers;
    created->use_gpu = acceleration.use_gpu;

    std::lock_guard<std::mutex> lock(g_warm_residency_mutex);
    auto existing = g_warm_sessions.find(key);
    if (existing != g_warm_sessions.end()) {
        if (
            existing->second->threads != threads ||
            existing->second->gpu_layers != acceleration.gpu_layers ||
            existing->second->use_gpu != acceleration.use_gpu
        ) {
            return nullptr;
        }
        return existing->second;
    }

    // Phase160: the optional native package has one mobile warm-residency lane. Text and MTMD
    // backends may be distinct routing surfaces, but keeping both large GGUF model states resident
    // defeats the bounded mobile-memory contract. The active generation retains its shared_ptr;
    // execution-group admission prevents sibling generation overlap in the governed runtime.
    g_warm_sessions.clear();
    g_warm_text_sessions.clear();
    g_warm_sessions.emplace(key, created);
    return created;
}

bool release_warm_session(const std::string & key) {
    std::lock_guard<std::mutex> lock(g_warm_residency_mutex);
    return g_warm_sessions.erase(key) > 0;
}

bool is_warm_session_resident(const std::string & key) {
    std::lock_guard<std::mutex> lock(g_warm_residency_mutex);
    return g_warm_sessions.find(key) != g_warm_sessions.end();
}


std::shared_ptr<WarmTextSession> get_or_load_warm_text_session(
    const std::string & key,
    const std::string & model_path,
    jint threads,
    std::atomic_bool * cancellation
) {
    const AccelerationProfile acceleration = acceleration_profile();
    {
        std::lock_guard<std::mutex> lock(g_warm_residency_mutex);
        auto it = g_warm_text_sessions.find(key);
        if (it != g_warm_text_sessions.end()) {
            if (
                it->second->threads != threads ||
                it->second->gpu_layers != acceleration.gpu_layers ||
                it->second->use_gpu != acceleration.use_gpu
            ) {
                return nullptr;
            }
            return it->second;
        }

        // Bound transition memory exactly as the MTMD path does: old warm residency is evicted
        // before a replacement model is allocated.
        g_warm_sessions.clear();
        g_warm_text_sessions.clear();
    }

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = acceleration.gpu_layers;
    model_params.progress_callback = model_load_progress_callback;
    model_params.progress_callback_user_data = cancellation;
    llama_model * raw_model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (raw_model == nullptr) return nullptr;
    if (cancellation != nullptr && cancellation->load(std::memory_order_relaxed)) {
        llama_model_free(raw_model);
        return nullptr;
    }

    auto created = std::make_shared<WarmTextSession>();
    created->model = raw_model;
    created->threads = threads;
    created->gpu_layers = acceleration.gpu_layers;
    created->use_gpu = acceleration.use_gpu;

    std::lock_guard<std::mutex> lock(g_warm_residency_mutex);
    auto existing = g_warm_text_sessions.find(key);
    if (existing != g_warm_text_sessions.end()) {
        if (
            existing->second->threads != threads ||
            existing->second->gpu_layers != acceleration.gpu_layers ||
            existing->second->use_gpu != acceleration.use_gpu
        ) {
            return nullptr;
        }
        return existing->second;
    }

    g_warm_sessions.clear();
    g_warm_text_sessions.clear();
    g_warm_text_sessions.emplace(key, created);
    return created;
}

bool release_warm_text_session(const std::string & key) {
    std::lock_guard<std::mutex> lock(g_warm_residency_mutex);
    return g_warm_text_sessions.erase(key) > 0;
}

bool is_warm_text_session_resident(const std::string & key) {
    std::lock_guard<std::mutex> lock(g_warm_residency_mutex);
    return g_warm_text_sessions.find(key) != g_warm_text_sessions.end();
}

bool abort_callback(void * data) {
    auto * flag = static_cast<std::atomic_bool *>(data);
    return flag != nullptr && flag->load(std::memory_order_relaxed);
}

bool model_load_progress_callback(float, void * data) {
    auto * flag = static_cast<std::atomic_bool *>(data);
    return flag == nullptr || !flag->load(std::memory_order_relaxed);
}

void throw_runtime(JNIEnv * env, const std::string & message) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls != nullptr) {
        env->ThrowNew(cls, message.c_str());
        env->DeleteLocalRef(cls);
    }
}

void append_standard_utf8(std::string & out, uint32_t code_point) {
    if (code_point <= 0x7fU) {
        out.push_back(static_cast<char>(code_point));
    } else if (code_point <= 0x7ffU) {
        out.push_back(static_cast<char>(0xc0U | (code_point >> 6U)));
        out.push_back(static_cast<char>(0x80U | (code_point & 0x3fU)));
    } else if (code_point <= 0xffffU) {
        out.push_back(static_cast<char>(0xe0U | (code_point >> 12U)));
        out.push_back(static_cast<char>(0x80U | ((code_point >> 6U) & 0x3fU)));
        out.push_back(static_cast<char>(0x80U | (code_point & 0x3fU)));
    } else {
        out.push_back(static_cast<char>(0xf0U | (code_point >> 18U)));
        out.push_back(static_cast<char>(0x80U | ((code_point >> 12U) & 0x3fU)));
        out.push_back(static_cast<char>(0x80U | ((code_point >> 6U) & 0x3fU)));
        out.push_back(static_cast<char>(0x80U | (code_point & 0x3fU)));
    }
}

std::string jstring_to_utf8(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const jsize length = env->GetStringLength(value);
    const jchar * chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return {};

    std::string out;
    out.reserve(static_cast<size_t>(length) * 3U);
    for (jsize i = 0; i < length; ++i) {
        uint32_t code_point = static_cast<uint32_t>(chars[i]);
        if (code_point >= 0xd800U && code_point <= 0xdbffU) {
            if (i + 1 < length) {
                const uint32_t low = static_cast<uint32_t>(chars[i + 1]);
                if (low >= 0xdc00U && low <= 0xdfffU) {
                    code_point =
                        0x10000U +
                        ((code_point - 0xd800U) << 10U) +
                        (low - 0xdc00U);
                    ++i;
                } else {
                    code_point = 0xfffdU;
                }
            } else {
                code_point = 0xfffdU;
            }
        } else if (code_point >= 0xdc00U && code_point <= 0xdfffU) {
            code_point = 0xfffdU;
        }
        append_standard_utf8(out, code_point);
    }
    env->ReleaseStringChars(value, chars);
    return out;
}

jbyteArray bytes_to_java(JNIEnv * env, const std::string & value) {
    if (value.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        throw_runtime(env, "native output byte sequence is too large for JNI");
        return nullptr;
    }
    const jsize size = static_cast<jsize>(value.size());
    jbyteArray bytes = env->NewByteArray(size);
    if (bytes == nullptr) return nullptr;
    if (size > 0) {
        env->SetByteArrayRegion(
            bytes,
            0,
            size,
            reinterpret_cast<const jbyte *>(value.data())
        );
        if (env->ExceptionCheck()) {
            env->DeleteLocalRef(bytes);
            return nullptr;
        }
    }
    return bytes;
}

int64_t elapsed_ms(
    const std::chrono::steady_clock::time_point & start,
    const std::chrono::steady_clock::time_point & end
) {
    return std::max<int64_t>(
        1,
        std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count()
    );
}

bool write_native_metrics(
    JNIEnv * env,
    jlongArray target,
    size_t prompt_tokens,
    int64_t prompt_eval_ms,
    int64_t generation_ms,
    size_t output_tokens
) {
    if (target == nullptr || env->GetArrayLength(target) < NATIVE_METRIC_COUNT) {
        throw_runtime(env, "native inference metrics buffer is missing or too small");
        return false;
    }
    const jlong values[NATIVE_METRIC_COUNT] = {
        static_cast<jlong>(prompt_tokens),
        static_cast<jlong>(prompt_eval_ms),
        static_cast<jlong>(generation_ms),
        static_cast<jlong>(output_tokens)
    };
    env->SetLongArrayRegion(target, 0, NATIVE_METRIC_COUNT, values);
    return !env->ExceptionCheck();
}

std::string token_piece(const llama_vocab * vocab, llama_token token) {
    std::vector<char> buffer(256);
    int32_t written = llama_token_to_piece(
        vocab,
        token,
        buffer.data(),
        static_cast<int32_t>(buffer.size()),
        0,
        false
    );
    if (written < 0) {
        buffer.resize(static_cast<size_t>(-written));
        written = llama_token_to_piece(
            vocab,
            token,
            buffer.data(),
            static_cast<int32_t>(buffer.size()),
            0,
            false
        );
    }
    if (written <= 0) return {};
    return std::string(buffer.data(), static_cast<size_t>(written));
}



llama_sampler * create_native_sampler(
    const llama_vocab * vocab,
    float temperature
) {
    if (vocab == nullptr) return nullptr;

    llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
    llama_sampler * sampler = llama_sampler_chain_init(sampler_params);
    if (sampler == nullptr) return nullptr;

    int32_t n_suppress = 0;
    const llama_token * suppress = llama_vocab_get_suppress_tokens(vocab, &n_suppress);
    if (n_suppress > 0 && suppress != nullptr) {
        std::vector<llama_logit_bias> biases;
        biases.reserve(static_cast<size_t>(n_suppress));
        for (int32_t i = 0; i < n_suppress; ++i) {
            biases.push_back(llama_logit_bias { suppress[i], -INFINITY });
        }

        llama_sampler * suppress_sampler = llama_sampler_init_logit_bias(
            llama_vocab_n_tokens(vocab),
            static_cast<int32_t>(biases.size()),
            biases.data()
        );
        if (suppress_sampler == nullptr) {
            llama_sampler_free(sampler);
            return nullptr;
        }
        llama_sampler_chain_add(sampler, suppress_sampler);
    }

    if (temperature <= 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }
    return sampler;
}

struct RenderedChatPrompt {
    std::string text;
    bool templated = false;
};

RenderedChatPrompt render_user_turn(
    const llama_model * model,
    const std::string & user_content
) {
    if (model == nullptr || user_content.empty()) {
        return RenderedChatPrompt { user_content, false };
    }

    const char * chat_template = llama_model_chat_template(model, nullptr);
    if (chat_template == nullptr || *chat_template == '\0') {
        return RenderedChatPrompt { user_content, false };
    }

    const llama_chat_message messages[] = {
        { "user", user_content.c_str() }
    };
    const int32_t required = llama_chat_apply_template(
        chat_template,
        messages,
        1,
        true,
        nullptr,
        0
    );
    if (required <= 0) {
        __android_log_print(
            ANDROID_LOG_WARN,
            TAG,
            "GGUF chat template is unavailable to the pinned llama.cpp renderer; using raw prompt"
        );
        return RenderedChatPrompt { user_content, false };
    }

    std::vector<char> buffer(static_cast<size_t>(required) + 1U, '\0');
    const int32_t written = llama_chat_apply_template(
        chat_template,
        messages,
        1,
        true,
        buffer.data(),
        static_cast<int32_t>(buffer.size())
    );
    if (written <= 0 || written > required) {
        __android_log_print(
            ANDROID_LOG_WARN,
            TAG,
            "GGUF chat template rendering failed; using raw prompt"
        );
        return RenderedChatPrompt { user_content, false };
    }

    return RenderedChatPrompt {
        std::string(buffer.data(), static_cast<size_t>(written)),
        true
    };
}

struct BitmapHolder {
    mtmd_bitmap * bitmap = nullptr;
    mtmd_helper_video * video = nullptr;

    ~BitmapHolder() {
        if (video != nullptr) mtmd_helper_video_free(video);
        if (bitmap != nullptr) mtmd_bitmap_free(bitmap);
    }

    BitmapHolder() = default;
    BitmapHolder(const BitmapHolder &) = delete;
    BitmapHolder & operator=(const BitmapHolder &) = delete;
    BitmapHolder(BitmapHolder && other) noexcept {
        bitmap = other.bitmap;
        video = other.video;
        other.bitmap = nullptr;
        other.video = nullptr;
    }
    BitmapHolder & operator=(BitmapHolder && other) noexcept {
        if (this == &other) return *this;
        if (video != nullptr) mtmd_helper_video_free(video);
        if (bitmap != nullptr) mtmd_bitmap_free(bitmap);
        bitmap = other.bitmap;
        video = other.video;
        other.bitmap = nullptr;
        other.video = nullptr;
        return *this;
    }
};

} // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_io_amper_neuroos_backend_AndroidMtmdNativeEngine_nativeSystemInfo(
    JNIEnv * env,
    jobject
) {
    init_backend_once();
    const char * info = llama_print_system_info();
    std::string detail = "llama.cpp ";
    detail += llama_version();
    detail += " + libmtmd";
    if (info != nullptr && *info != '\0') {
        detail += " | ";
        detail += info;
    }
    return env->NewStringUTF(detail.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_io_amper_neuroos_backend_AndroidMtmdNativeEngine_nativeAccelerationIdentity(
    JNIEnv * env,
    jobject
) {
    init_backend_once();
    const auto profile = acceleration_profile();
    return env->NewStringUTF(profile.identity.c_str());
}

extern "C"
JNIEXPORT jint JNICALL
Java_io_amper_neuroos_backend_AndroidMtmdNativeEngine_nativeProbeProjector(
    JNIEnv * env,
    jobject,
    jstring projector_path
) {
    init_backend_once();
    const std::string path = jstring_to_utf8(env, projector_path);
    if (path.empty()) {
        throw_runtime(env, "projector path is blank");
        return 0;
    }
    const mtmd_caps caps = mtmd_get_cap_from_file(path.c_str());
    jint result = 0;
    if (caps.inp_vision) result |= CAP_IMAGE;
    if (caps.inp_audio) result |= CAP_AUDIO;
    return result;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_io_amper_neuroos_backend_AndroidMtmdNativeEngine_nativeCancel(
    JNIEnv *,
    jobject,
    jlong request_id
) {
    auto flag = find_request(request_id);
    if (!flag) return JNI_FALSE;
    flag->store(true, std::memory_order_relaxed);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_io_amper_neuroos_backend_AndroidMtmdNativeEngine_nativeReleaseRequest(
    JNIEnv *,
    jobject,
    jlong request_id
) {
    erase_request(request_id);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_io_amper_neuroos_backend_AndroidMtmdNativeEngine_nativePrepareWarmTextSession(
    JNIEnv * env,
    jobject,
    jlong request_id,
    jstring session_key_value,
    jstring model_path_value,
    jint threads
) {
    init_backend_once();
    auto cancelled = create_request(request_id);
    const std::string session_key = jstring_to_utf8(env, session_key_value);
    const std::string model_path = jstring_to_utf8(env, model_path_value);
    if (session_key.empty() || model_path.empty() || threads <= 0) {
        throw_runtime(env, "invalid warm native text preparation arguments");
        return JNI_FALSE;
    }
    if (cancelled->load(std::memory_order_relaxed)) {
        throw_runtime(env, CANCELLED);
        return JNI_FALSE;
    }

    auto session = get_or_load_warm_text_session(
        session_key,
        model_path,
        threads,
        cancelled.get()
    );
    if (cancelled->load(std::memory_order_relaxed)) {
        release_warm_text_session(session_key);
        throw_runtime(env, CANCELLED);
        return JNI_FALSE;
    }
    if (session == nullptr) {
        throw_runtime(env, "failed to prepare warm native text session");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_io_amper_neuroos_backend_AndroidMtmdNativeEngine_nativePrepareWarmSession(
    JNIEnv * env,
    jobject,
    jlong request_id,
    jstring session_key_value,
    jstring model_path_value,
    jstring projector_path_value,
    jint threads
) {
    init_backend_once();
    auto cancelled = create_request(request_id);
    const std::string session_key = jstring_to_utf8(env, session_key_value);
    const std::string model_path = jstring_to_utf8(env, model_path_value);
    const std::string projector_path = jstring_to_utf8(env, projector_path_value);
    if (
        session_key.empty() ||
        model_path.empty() ||
        projector_path.empty() ||
        threads <= 0
    ) {
        throw_runtime(env, "invalid warm MTMD preparation arguments");
        return JNI_FALSE;
    }
    if (cancelled->load(std::memory_order_relaxed)) {
        throw_runtime(env, CANCELLED);
        return JNI_FALSE;
    }

    auto session = get_or_load_warm_session(
        session_key,
        model_path,
        projector_path,
        threads,
        cancelled.get()
    );
    if (cancelled->load(std::memory_order_relaxed)) {
        release_warm_session(session_key);
        throw_runtime(env, CANCELLED);
        return JNI_FALSE;
    }
    if (session == nullptr) {
        throw_runtime(env, "failed to prepare warm MTMD session");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_io_amper_neuroos_backend_AndroidMtmdNativeEngine_nativeReleaseWarmSession(
    JNIEnv * env,
    jobject,
    jstring session_key_value
) {
    const std::string session_key = jstring_to_utf8(env, session_key_value);
    if (session_key.empty()) return JNI_FALSE;
    return release_warm_session(session_key) ? JNI_TRUE : JNI_FALSE;
}


extern "C"
JNIEXPORT jboolean JNICALL
Java_io_amper_neuroos_backend_AndroidMtmdNativeEngine_nativeReleaseWarmTextSession(
    JNIEnv * env,
    jobject,
    jstring session_key_value
) {
    const std::string session_key = jstring_to_utf8(env, session_key_value);
    if (session_key.empty()) return JNI_FALSE;
    return release_warm_text_session(session_key) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jint JNICALL
Java_io_amper_neuroos_backend_AndroidMtmdNativeEngine_nativeEstimateWarmTextPromptTokens(
    JNIEnv * env,
    jobject,
    jstring session_key_value,
    jstring prompt_value
) {
    init_backend_once();

    const std::string session_key = jstring_to_utf8(env, session_key_value);
    const std::string prompt = jstring_to_utf8(env, prompt_value);
    if (session_key.empty() || prompt.empty()) {
        throw_runtime(env, "warm tokenizer session key and prompt must be non-empty");
        return 0;
    }

    std::shared_ptr<WarmTextSession> session;
    {
        std::lock_guard<std::mutex> lock(g_warm_residency_mutex);
        auto it = g_warm_text_sessions.find(session_key);
        if (it == g_warm_text_sessions.end()) {
            throw_runtime(env, "warm native text session is not resident");
            return 0;
        }
        session = it->second;
    }
    if (session == nullptr || session->model == nullptr) {
        throw_runtime(env, "warm native text session has no model");
        return 0;
    }

    const RenderedChatPrompt rendered = render_user_turn(session->model, prompt);
    const std::string & model_prompt = rendered.text;
    if (model_prompt.size() > static_cast<size_t>(std::numeric_limits<int32_t>::max())) {
        throw_runtime(env, "warm native text prompt is too large to tokenize");
        return 0;
    }

    const llama_vocab * vocab = llama_model_get_vocab(session->model);
    int32_t token_count = llama_tokenize(
        vocab,
        model_prompt.data(),
        static_cast<int32_t>(model_prompt.size()),
        nullptr,
        0,
        true,
        true
    );
    if (token_count == std::numeric_limits<int32_t>::min()) {
        throw_runtime(env, "warm native text prompt token count overflow");
        return 0;
    }
    if (token_count < 0) token_count = -token_count;
    if (token_count <= 0) {
        throw_runtime(env, "warm native text prompt produced no tokens");
        return 0;
    }
    return static_cast<jint>(token_count);
}

extern "C"
JNIEXPORT jint JNICALL
Java_io_amper_neuroos_backend_AndroidMtmdNativeEngine_nativeEstimateWarmMtmdPromptTokens(
    JNIEnv * env,
    jobject,
    jstring session_key_value,
    jstring prompt_value,
    jobjectArray payloads,
    jintArray kinds_value
) {
    init_backend_once();

    const std::string session_key = jstring_to_utf8(env, session_key_value);
    const std::string prompt = jstring_to_utf8(env, prompt_value);
    if (
        session_key.empty() ||
        prompt.empty() ||
        payloads == nullptr ||
        kinds_value == nullptr
    ) {
        throw_runtime(env, "warm MTMD tokenizer arguments are missing");
        return 0;
    }

    const jsize payload_count = env->GetArrayLength(payloads);
    const jsize kind_count = env->GetArrayLength(kinds_value);
    if (payload_count <= 0 || payload_count != kind_count) {
        throw_runtime(env, "warm MTMD attachment payload/kind count mismatch");
        return 0;
    }

    std::shared_ptr<WarmSession> session;
    {
        std::lock_guard<std::mutex> lock(g_warm_residency_mutex);
        auto it = g_warm_sessions.find(session_key);
        if (it == g_warm_sessions.end()) {
            throw_runtime(env, "warm MTMD session is not resident");
            return 0;
        }
        session = it->second;
    }
    if (session == nullptr || session->model == nullptr || session->mm == nullptr) {
        throw_runtime(env, "warm MTMD session is incomplete");
        return 0;
    }

    jint * kinds = env->GetIntArrayElements(kinds_value, nullptr);
    if (kinds == nullptr) {
        throw_runtime(env, "unable to read warm MTMD attachment kinds");
        return 0;
    }

    std::vector<BitmapHolder> holders;
    std::vector<const mtmd_bitmap *> bitmap_ptrs;
    holders.reserve(static_cast<size_t>(payload_count));
    bitmap_ptrs.reserve(static_cast<size_t>(payload_count));
    mtmd_helper_init_opt helper_opt = mtmd_helper_init_opt_default();

    bool attachment_error = false;
    std::string attachment_error_message;
    for (jsize i = 0; i < payload_count; ++i) {
        auto bytes = static_cast<jbyteArray>(env->GetObjectArrayElement(payloads, i));
        if (bytes == nullptr) {
            attachment_error = true;
            attachment_error_message = "warm MTMD attachment payload is null";
            break;
        }

        const jsize size = env->GetArrayLength(bytes);
        jbyte * data = env->GetByteArrayElements(bytes, nullptr);
        if (data == nullptr || size <= 0) {
            if (data != nullptr) env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
            env->DeleteLocalRef(bytes);
            attachment_error = true;
            attachment_error_message = "warm MTMD attachment payload is empty";
            break;
        }

        auto wrapper = mtmd_helper_bitmap_init_from_buf(
            session->mm,
            reinterpret_cast<const unsigned char *>(data),
            static_cast<size_t>(size),
            false,
            helper_opt
        );
        env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
        env->DeleteLocalRef(bytes);

        if (wrapper.bitmap == nullptr) {
            if (wrapper.video_ctx != nullptr) mtmd_helper_video_free(wrapper.video_ctx);
            attachment_error = true;
            attachment_error_message = "libmtmd could not decode warm-preflight attachment";
            break;
        }
        if (wrapper.video_ctx != nullptr) {
            mtmd_helper_video_free(wrapper.video_ctx);
            mtmd_bitmap_free(wrapper.bitmap);
            attachment_error = true;
            attachment_error_message = "video attachments are not admitted";
            break;
        }

        const bool actual_audio = mtmd_bitmap_is_audio(wrapper.bitmap);
        const bool declared_audio = kinds[i] == KIND_AUDIO;
        const bool declared_image = kinds[i] == KIND_IMAGE;
        if ((!declared_audio && !declared_image) || actual_audio != declared_audio) {
            mtmd_bitmap_free(wrapper.bitmap);
            attachment_error = true;
            attachment_error_message =
                "warm MTMD attachment bytes do not match declared modality";
            break;
        }

        BitmapHolder holder;
        holder.bitmap = wrapper.bitmap;
        holders.emplace_back(std::move(holder));
        bitmap_ptrs.push_back(holders.back().bitmap);
    }
    env->ReleaseIntArrayElements(kinds_value, kinds, JNI_ABORT);

    if (attachment_error) {
        throw_runtime(env, attachment_error_message);
        return 0;
    }

    const char * marker_c = mtmd_get_marker(session->mm);
    if (marker_c == nullptr || *marker_c == '\0') marker_c = mtmd_default_marker();

    std::string multimodal_user_content;
    for (jsize i = 0; i < payload_count; ++i) {
        multimodal_user_content += marker_c;
        multimodal_user_content += "\n";
    }
    multimodal_user_content += prompt;

    const RenderedChatPrompt rendered_prompt =
        render_user_turn(session->model, multimodal_user_content);
    const std::string & multimodal_prompt = rendered_prompt.text;

    mtmd_input_text input_text {
        multimodal_prompt.data(),
        multimodal_prompt.size(),
        true,
        true
    };

    mtmd_input_chunks * raw_chunks = mtmd_input_chunks_init();
    if (raw_chunks == nullptr) {
        throw_runtime(env, "failed to allocate warm MTMD preflight chunks");
        return 0;
    }
    std::unique_ptr<mtmd_input_chunks, decltype(&mtmd_input_chunks_free)> chunks(
        raw_chunks,
        &mtmd_input_chunks_free
    );

    const int32_t tokenize_result = mtmd_tokenize(
        session->mm,
        chunks.get(),
        &input_text,
        bitmap_ptrs.data(),
        bitmap_ptrs.size()
    );
    if (tokenize_result != 0) {
        throw_runtime(
            env,
            "warm MTMD tokenization failed: code=" + std::to_string(tokenize_result)
        );
        return 0;
    }

    const size_t token_count = mtmd_helper_get_n_tokens(chunks.get());
    if (
        token_count == 0 ||
        token_count > static_cast<size_t>(std::numeric_limits<int32_t>::max())
    ) {
        throw_runtime(env, "warm MTMD prompt token count is out of range");
        return 0;
    }
    return static_cast<jint>(token_count);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_io_amper_neuroos_backend_AndroidMtmdNativeEngine_nativeIsWarmTextSessionResident(
    JNIEnv * env,
    jobject,
    jstring session_key_value
) {
    const std::string session_key = jstring_to_utf8(env, session_key_value);
    if (session_key.empty()) return JNI_FALSE;
    return is_warm_text_session_resident(session_key) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_io_amper_neuroos_backend_AndroidMtmdNativeEngine_nativeIsWarmMtmdSessionResident(
    JNIEnv * env,
    jobject,
    jstring session_key_value
) {
    const std::string session_key = jstring_to_utf8(env, session_key_value);
    if (session_key.empty()) return JNI_FALSE;
    return is_warm_session_resident(session_key) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_io_amper_neuroos_backend_AndroidMtmdNativeEngine_nativeGenerateText(
    JNIEnv * env,
    jobject,
    jlong request_id,
    jstring session_key_value,
    jstring model_path_value,
    jstring prompt_value,
    jint max_output_tokens,
    jfloat temperature,
    jint threads,
    jint context_tokens,
    jlongArray native_metrics,
    jobject sink
) {
    init_backend_once();

    const std::string session_key = jstring_to_utf8(env, session_key_value);
    const std::string model_path = jstring_to_utf8(env, model_path_value);
    const std::string prompt = jstring_to_utf8(env, prompt_value);
    if (model_path.empty() || prompt.empty()) {
        throw_runtime(env, "model and prompt must be non-empty");
        return nullptr;
    }
    if (max_output_tokens <= 0 || threads <= 0 || context_tokens <= 0) {
        throw_runtime(env, "invalid native text generation limits");
        return nullptr;
    }
    if (native_metrics == nullptr || sink == nullptr) {
        throw_runtime(env, "native text metrics/token sink is missing");
        return nullptr;
    }
    if (env->GetArrayLength(native_metrics) < NATIVE_METRIC_COUNT) {
        throw_runtime(env, "native text metrics buffer is too small");
        return nullptr;
    }

    auto cancelled = create_request(request_id);

    std::shared_ptr<WarmTextSession> warm_session;
    std::unique_ptr<llama_model, decltype(&llama_model_free)> cold_model(
        nullptr,
        &llama_model_free
    );
    llama_model * model_ptr = nullptr;

    if (!session_key.empty()) {
        warm_session = get_or_load_warm_text_session(
            session_key,
            model_path,
            threads,
            cancelled.get()
        );
        if (warm_session == nullptr) {
            if (cancelled->load(std::memory_order_relaxed)) {
                throw_runtime(env, CANCELLED);
            } else {
                throw_runtime(env, "failed to prepare warm GGUF text session");
            }
            return nullptr;
        }
        model_ptr = warm_session->model;
    } else {
        const AccelerationProfile acceleration = acceleration_profile();
        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = acceleration.gpu_layers;
        model_params.progress_callback = model_load_progress_callback;
        model_params.progress_callback_user_data = cancelled.get();
        llama_model * raw_model = llama_model_load_from_file(model_path.c_str(), model_params);
        if (raw_model == nullptr) {
            if (cancelled->load(std::memory_order_relaxed)) {
                throw_runtime(env, CANCELLED);
            } else {
                throw_runtime(env, "failed to load GGUF text model");
            }
            return nullptr;
        }
        if (cancelled->load(std::memory_order_relaxed)) {
            llama_model_free(raw_model);
            throw_runtime(env, CANCELLED);
            return nullptr;
        }
        cold_model.reset(raw_model);
        model_ptr = cold_model.get();
    }

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = static_cast<uint32_t>(context_tokens);
    context_params.n_batch = static_cast<uint32_t>(std::min(context_tokens, 1024));
    context_params.n_ubatch = static_cast<uint32_t>(std::min(context_tokens, 512));
    context_params.n_threads = threads;
    context_params.n_threads_batch = threads;
    const AccelerationProfile request_acceleration = acceleration_profile();
    context_params.offload_kqv = request_acceleration.use_gpu;
    context_params.op_offload = request_acceleration.use_gpu;
    context_params.abort_callback = abort_callback;
    context_params.abort_callback_data = cancelled.get();

    llama_context * raw_context = llama_init_from_model(model_ptr, context_params);
    if (raw_context == nullptr) {
        throw_runtime(env, "failed to create native text llama context");
        return nullptr;
    }
    std::unique_ptr<llama_context, decltype(&llama_free)> context(raw_context, &llama_free);

    if (cancelled->load(std::memory_order_relaxed)) {
        throw_runtime(env, CANCELLED);
        return nullptr;
    }

    const RenderedChatPrompt rendered_prompt = render_user_turn(model_ptr, prompt);
    const std::string & model_prompt = rendered_prompt.text;
    if (model_prompt.size() > static_cast<size_t>(std::numeric_limits<int32_t>::max())) {
        throw_runtime(env, "native text prompt is too large to tokenize");
        return nullptr;
    }

    const llama_vocab * vocab = llama_model_get_vocab(model_ptr);
    int32_t token_count = llama_tokenize(
        vocab,
        model_prompt.data(),
        static_cast<int32_t>(model_prompt.size()),
        nullptr,
        0,
        true,
        true
    );
    if (token_count == std::numeric_limits<int32_t>::min()) {
        throw_runtime(env, "native text prompt token count overflow");
        return nullptr;
    }
    if (token_count < 0) token_count = -token_count;
    if (token_count <= 0) {
        throw_runtime(env, "native text prompt produced no tokens");
        return nullptr;
    }

    std::vector<llama_token> prompt_tokens(static_cast<size_t>(token_count));
    const int32_t actual_tokens = llama_tokenize(
        vocab,
        model_prompt.data(),
        static_cast<int32_t>(model_prompt.size()),
        prompt_tokens.data(),
        token_count,
        true,
        true
    );
    if (actual_tokens <= 0) {
        throw_runtime(env, "native text prompt tokenization failed");
        return nullptr;
    }
    prompt_tokens.resize(static_cast<size_t>(actual_tokens));

    if (
        prompt_tokens.size() + static_cast<size_t>(max_output_tokens) >
        static_cast<size_t>(context_tokens)
    ) {
        throw_runtime(
            env,
            "text prompt exceeds admitted context window: prompt=" +
                std::to_string(prompt_tokens.size()) +
                " output=" + std::to_string(max_output_tokens) +
                " context=" + std::to_string(context_tokens)
        );
        return nullptr;
    }

    const auto prompt_started = std::chrono::steady_clock::now();
    size_t prompt_offset = 0;
    while (prompt_offset < prompt_tokens.size()) {
        if (cancelled->load(std::memory_order_relaxed)) {
            throw_runtime(env, CANCELLED);
            return nullptr;
        }

        const size_t remaining = prompt_tokens.size() - prompt_offset;
        const int32_t batch_tokens = static_cast<int32_t>(
            std::min(
                remaining,
                static_cast<size_t>(context_params.n_batch)
            )
        );
        llama_batch batch = llama_batch_get_one(
            prompt_tokens.data() + prompt_offset,
            batch_tokens
        );
        const int32_t decode_result = llama_decode(context.get(), batch);
        if (decode_result != 0) {
            if (cancelled->load(std::memory_order_relaxed) || decode_result == 2) {
                throw_runtime(env, CANCELLED);
            } else {
                throw_runtime(
                    env,
                    "native text prompt decode failed: code=" +
                        std::to_string(decode_result)
                );
            }
            return nullptr;
        }
        prompt_offset += static_cast<size_t>(batch_tokens);
    }
    const auto prompt_finished = std::chrono::steady_clock::now();

    llama_sampler * raw_sampler = create_native_sampler(vocab, temperature);
    if (raw_sampler == nullptr) {
        throw_runtime(env, "failed to allocate native text sampler");
        return nullptr;
    }
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
        raw_sampler,
        &llama_sampler_free
    );

    jclass sink_class = env->GetObjectClass(sink);
    if (sink_class == nullptr) {
        throw_runtime(env, "native text token sink class unavailable");
        return nullptr;
    }
    jmethodID on_token = env->GetMethodID(sink_class, "onBytes", "([B)V");
    env->DeleteLocalRef(sink_class);
    if (on_token == nullptr) {
        throw_runtime(env, "native text token sink method unavailable");
        return nullptr;
    }

    std::string output;
    output.reserve(static_cast<size_t>(max_output_tokens) * 4U);
    size_t output_tokens = 0;
    const auto generation_started = std::chrono::steady_clock::now();

    for (int32_t i = 0; i < max_output_tokens; ++i) {
        if (cancelled->load(std::memory_order_relaxed)) {
            throw_runtime(env, CANCELLED);
            return nullptr;
        }

        llama_token token = llama_sampler_sample(sampler.get(), context.get(), -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        output_tokens += 1U;

        const std::string piece = token_piece(vocab, token);
        if (!piece.empty()) {
            output += piece;
            jbyteArray jpiece = bytes_to_java(env, piece);
            if (jpiece == nullptr) {
                if (!env->ExceptionCheck()) {
                    throw_runtime(env, "unable to allocate streamed native text token bytes");
                }
                return nullptr;
            }
            env->CallVoidMethod(sink, on_token, jpiece);
            env->DeleteLocalRef(jpiece);
            if (env->ExceptionCheck()) {
                cancelled->store(true, std::memory_order_relaxed);
                return nullptr;
            }
        }

        llama_batch batch = llama_batch_get_one(&token, 1);
        const int32_t decode_result = llama_decode(context.get(), batch);
        if (decode_result != 0) {
            if (cancelled->load(std::memory_order_relaxed) || decode_result == 2) {
                throw_runtime(env, CANCELLED);
            } else {
                throw_runtime(
                    env,
                    "native text decode failed: code=" +
                        std::to_string(decode_result)
                );
            }
            return nullptr;
        }
    }

    const auto generation_finished = std::chrono::steady_clock::now();
    if (
        !write_native_metrics(
            env,
            native_metrics,
            prompt_tokens.size(),
            elapsed_ms(prompt_started, prompt_finished),
            elapsed_ms(generation_started, generation_finished),
            output_tokens
        )
    ) {
        return nullptr;
    }
    return bytes_to_java(env, output);
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_io_amper_neuroos_backend_AndroidMtmdNativeEngine_nativeGenerate(
    JNIEnv * env,
    jobject,
    jlong request_id,
    jstring session_key_value,
    jstring model_path_value,
    jstring projector_path_value,
    jstring prompt_value,
    jobjectArray payloads,
    jintArray kinds_value,
    jint max_output_tokens,
    jfloat temperature,
    jint threads,
    jint context_tokens,
    jlongArray native_metrics,
    jobject sink
) {
    init_backend_once();

    const std::string session_key = jstring_to_utf8(env, session_key_value);
    const std::string model_path = jstring_to_utf8(env, model_path_value);
    const std::string projector_path = jstring_to_utf8(env, projector_path_value);
    const std::string prompt = jstring_to_utf8(env, prompt_value);
    if (model_path.empty() || projector_path.empty() || prompt.empty()) {
        throw_runtime(env, "model, projector and prompt must be non-empty");
        return nullptr;
    }
    if (max_output_tokens <= 0 || threads <= 0 || context_tokens <= 0) {
        throw_runtime(env, "invalid native MTMD generation limits");
        return nullptr;
    }
    if (
        payloads == nullptr ||
        kinds_value == nullptr ||
        native_metrics == nullptr ||
        sink == nullptr
    ) {
        throw_runtime(env, "native MTMD request arguments are missing");
        return nullptr;
    }
    if (env->GetArrayLength(native_metrics) < NATIVE_METRIC_COUNT) {
        throw_runtime(env, "native MTMD metrics buffer is too small");
        return nullptr;
    }

    const jsize payload_count = env->GetArrayLength(payloads);
    const jsize kind_count = env->GetArrayLength(kinds_value);
    if (payload_count != kind_count) {
        throw_runtime(env, "attachment payload/kind count mismatch");
        return nullptr;
    }

    auto cancelled = create_request(request_id);

    std::shared_ptr<WarmSession> warm_session;
    std::unique_ptr<llama_model, decltype(&llama_model_free)> cold_model(
        nullptr,
        &llama_model_free
    );
    std::unique_ptr<mtmd_context, decltype(&mtmd_free)> cold_mm(
        nullptr,
        &mtmd_free
    );
    llama_model * model_ptr = nullptr;
    mtmd_context * mm_ptr = nullptr;

    if (!session_key.empty()) {
        warm_session = get_or_load_warm_session(
            session_key,
            model_path,
            projector_path,
            threads,
            cancelled.get()
        );
        if (warm_session == nullptr) {
            if (cancelled->load(std::memory_order_relaxed)) {
                throw_runtime(env, CANCELLED);
            } else {
                throw_runtime(env, "failed to prepare warm GGUF/mmproj session");
            }
            return nullptr;
        }
        model_ptr = warm_session->model;
        mm_ptr = warm_session->mm;
    } else {
        const AccelerationProfile acceleration = acceleration_profile();
        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = acceleration.gpu_layers;
        model_params.progress_callback = model_load_progress_callback;
        model_params.progress_callback_user_data = cancelled.get();
        llama_model * raw_model = llama_model_load_from_file(model_path.c_str(), model_params);
        if (raw_model == nullptr) {
            if (cancelled->load(std::memory_order_relaxed)) {
                throw_runtime(env, CANCELLED);
            } else {
                throw_runtime(env, "failed to load GGUF text model");
            }
            return nullptr;
        }
        if (cancelled->load(std::memory_order_relaxed)) {
            llama_model_free(raw_model);
            throw_runtime(env, CANCELLED);
            return nullptr;
        }
        cold_model.reset(raw_model);
        model_ptr = cold_model.get();

        mtmd_context_params mm_params = mtmd_context_params_default();
        mm_params.use_gpu = acceleration.use_gpu;
        mm_params.print_timings = false;
        mm_params.n_threads = threads;
        mm_params.warmup = false;
        mm_params.progress_callback = model_load_progress_callback;
        mm_params.progress_callback_user_data = cancelled.get();

        if (cancelled->load(std::memory_order_relaxed)) {
            throw_runtime(env, CANCELLED);
            return nullptr;
        }
        mtmd_context * raw_mm = mtmd_init_from_file(
            projector_path.c_str(),
            model_ptr,
            mm_params
        );
        if (raw_mm == nullptr) {
            if (cancelled->load(std::memory_order_relaxed)) {
                throw_runtime(env, CANCELLED);
            } else {
                throw_runtime(env, "failed to load mmproj with libmtmd");
            }
            return nullptr;
        }
        cold_mm.reset(raw_mm);
        if (cancelled->load(std::memory_order_relaxed)) {
            throw_runtime(env, CANCELLED);
            return nullptr;
        }
        mm_ptr = cold_mm.get();
    }

    // A fresh llama_context is intentionally created for every request. Warm reuse keeps only
    // immutable model/projector runtime state and never carries KV cache or conversation state.
    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = static_cast<uint32_t>(context_tokens);
    context_params.n_batch = static_cast<uint32_t>(std::min(context_tokens, 1024));
    context_params.n_ubatch = static_cast<uint32_t>(std::min(context_tokens, 512));
    context_params.n_threads = threads;
    context_params.n_threads_batch = threads;
    const AccelerationProfile request_acceleration = acceleration_profile();
    context_params.offload_kqv = request_acceleration.use_gpu;
    context_params.op_offload = request_acceleration.use_gpu;
    context_params.abort_callback = abort_callback;
    context_params.abort_callback_data = cancelled.get();

    llama_context * raw_context = llama_init_from_model(model_ptr, context_params);
    if (raw_context == nullptr) {
        throw_runtime(env, "failed to create llama context");
        return nullptr;
    }
    std::unique_ptr<llama_context, decltype(&llama_free)> context(raw_context, &llama_free);

    jint * kinds = env->GetIntArrayElements(kinds_value, nullptr);
    if (kinds == nullptr) {
        throw_runtime(env, "unable to read attachment kinds");
        return nullptr;
    }

    std::vector<BitmapHolder> holders;
    std::vector<const mtmd_bitmap *> bitmap_ptrs;
    holders.reserve(static_cast<size_t>(payload_count));
    bitmap_ptrs.reserve(static_cast<size_t>(payload_count));
    mtmd_helper_init_opt helper_opt = mtmd_helper_init_opt_default();

    bool attachment_error = false;
    std::string attachment_error_message;
    for (jsize i = 0; i < payload_count; ++i) {
        auto bytes = static_cast<jbyteArray>(env->GetObjectArrayElement(payloads, i));
        if (bytes == nullptr) {
            attachment_error = true;
            attachment_error_message = "attachment payload is null";
            break;
        }
        const jsize size = env->GetArrayLength(bytes);
        jbyte * data = env->GetByteArrayElements(bytes, nullptr);
        if (data == nullptr || size <= 0) {
            if (data != nullptr) env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
            env->DeleteLocalRef(bytes);
            attachment_error = true;
            attachment_error_message = "attachment payload is empty";
            break;
        }

        auto wrapper = mtmd_helper_bitmap_init_from_buf(
            mm_ptr,
            reinterpret_cast<const unsigned char *>(data),
            static_cast<size_t>(size),
            false,
            helper_opt
        );
        env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
        env->DeleteLocalRef(bytes);

        if (wrapper.bitmap == nullptr) {
            if (wrapper.video_ctx != nullptr) mtmd_helper_video_free(wrapper.video_ctx);
            attachment_error = true;
            attachment_error_message = "libmtmd could not decode attachment";
            break;
        }
        if (wrapper.video_ctx != nullptr) {
            mtmd_helper_video_free(wrapper.video_ctx);
            mtmd_bitmap_free(wrapper.bitmap);
            attachment_error = true;
            attachment_error_message = "video attachments are not admitted by Phase148";
            break;
        }

        const bool actual_audio = mtmd_bitmap_is_audio(wrapper.bitmap);
        const bool declared_audio = kinds[i] == KIND_AUDIO;
        const bool declared_image = kinds[i] == KIND_IMAGE;
        if ((!declared_audio && !declared_image) || actual_audio != declared_audio) {
            mtmd_bitmap_free(wrapper.bitmap);
            attachment_error = true;
            attachment_error_message = "attachment bytes do not match declared modality";
            break;
        }

        BitmapHolder holder;
        holder.bitmap = wrapper.bitmap;
        holders.emplace_back(std::move(holder));
        bitmap_ptrs.push_back(holders.back().bitmap);
    }
    env->ReleaseIntArrayElements(kinds_value, kinds, JNI_ABORT);

    if (attachment_error) {
        throw_runtime(env, attachment_error_message);
        return nullptr;
    }

    if (cancelled->load(std::memory_order_relaxed)) {
        throw_runtime(env, CANCELLED);
        return nullptr;
    }

    const char * marker_c = mtmd_get_marker(mm_ptr);
    if (marker_c == nullptr || *marker_c == '\0') marker_c = mtmd_default_marker();
    std::string multimodal_user_content;
    for (jsize i = 0; i < payload_count; ++i) {
        multimodal_user_content += marker_c;
        multimodal_user_content += "\n";
    }
    multimodal_user_content += prompt;

    const RenderedChatPrompt rendered_prompt =
        render_user_turn(model_ptr, multimodal_user_content);
    const std::string & multimodal_prompt = rendered_prompt.text;

    mtmd_input_text input_text {
        multimodal_prompt.data(),
        multimodal_prompt.size(),
        true,
        true
    };

    mtmd_input_chunks * raw_chunks = mtmd_input_chunks_init();
    if (raw_chunks == nullptr) {
        throw_runtime(env, "failed to allocate libmtmd input chunks");
        return nullptr;
    }
    std::unique_ptr<mtmd_input_chunks, decltype(&mtmd_input_chunks_free)> chunks(
        raw_chunks,
        &mtmd_input_chunks_free
    );

    const int32_t tokenize_result = mtmd_tokenize(
        mm_ptr,
        chunks.get(),
        &input_text,
        bitmap_ptrs.empty() ? nullptr : bitmap_ptrs.data(),
        bitmap_ptrs.size()
    );
    if (tokenize_result != 0) {
        throw_runtime(env, "libmtmd tokenization failed: code=" + std::to_string(tokenize_result));
        return nullptr;
    }

    const size_t prompt_tokens = mtmd_helper_get_n_tokens(chunks.get());
    if (prompt_tokens + static_cast<size_t>(max_output_tokens) >
        static_cast<size_t>(context_tokens)) {
        throw_runtime(
            env,
            "multimodal prompt exceeds admitted context window: prompt=" +
                std::to_string(prompt_tokens) +
                " output=" + std::to_string(max_output_tokens) +
                " context=" + std::to_string(context_tokens)
        );
        return nullptr;
    }

    llama_pos n_past = 0;
    const auto prompt_started = std::chrono::steady_clock::now();
    const int32_t eval_result = mtmd_helper_eval_chunks(
        mm_ptr,
        context.get(),
        chunks.get(),
        0,
        0,
        static_cast<int32_t>(context_params.n_batch),
        true,
        &n_past
    );
    if (eval_result != 0) {
        if (cancelled->load(std::memory_order_relaxed)) {
            throw_runtime(env, CANCELLED);
        } else {
            throw_runtime(env, "libmtmd prompt evaluation failed: code=" + std::to_string(eval_result));
        }
        return nullptr;
    }

    if (cancelled->load(std::memory_order_relaxed)) {
        throw_runtime(env, CANCELLED);
        return nullptr;
    }
    const auto prompt_finished = std::chrono::steady_clock::now();

    llama_sampler * raw_sampler = create_native_sampler(
        llama_model_get_vocab(model_ptr),
        temperature
    );
    if (raw_sampler == nullptr) {
        throw_runtime(env, "failed to allocate llama sampler");
        return nullptr;
    }
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
        raw_sampler,
        &llama_sampler_free
    );

    jclass sink_class = env->GetObjectClass(sink);
    if (sink_class == nullptr) {
        throw_runtime(env, "native token sink class unavailable");
        return nullptr;
    }
    jmethodID on_token = env->GetMethodID(sink_class, "onBytes", "([B)V");
    env->DeleteLocalRef(sink_class);
    if (on_token == nullptr) {
        throw_runtime(env, "native token sink method unavailable");
        return nullptr;
    }

    const llama_vocab * vocab = llama_model_get_vocab(model_ptr);
    std::string output;
    output.reserve(static_cast<size_t>(max_output_tokens) * 4U);
    size_t output_tokens = 0;
    const auto generation_started = std::chrono::steady_clock::now();

    for (int32_t i = 0; i < max_output_tokens; ++i) {
        if (cancelled->load(std::memory_order_relaxed)) {
            throw_runtime(env, CANCELLED);
            return nullptr;
        }

        llama_token token = llama_sampler_sample(sampler.get(), context.get(), -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        output_tokens += 1U;

        const std::string piece = token_piece(vocab, token);
        if (!piece.empty()) {
            output += piece;
            jbyteArray jpiece = bytes_to_java(env, piece);
            if (jpiece == nullptr) {
                if (!env->ExceptionCheck()) {
                    throw_runtime(env, "unable to allocate streamed MTMD token bytes");
                }
                return nullptr;
            }
            env->CallVoidMethod(sink, on_token, jpiece);
            env->DeleteLocalRef(jpiece);
            if (env->ExceptionCheck()) {
                cancelled->store(true, std::memory_order_relaxed);
                return nullptr;
            }
        }

        llama_batch batch = llama_batch_get_one(&token, 1);
        const int32_t decode_result = llama_decode(context.get(), batch);
        if (decode_result != 0) {
            if (cancelled->load(std::memory_order_relaxed) || decode_result == 2) {
                throw_runtime(env, CANCELLED);
            } else {
                throw_runtime(env, "llama decode failed: code=" + std::to_string(decode_result));
            }
            return nullptr;
        }
    }

    const auto generation_finished = std::chrono::steady_clock::now();
    if (
        !write_native_metrics(
            env,
            native_metrics,
            prompt_tokens,
            elapsed_ms(prompt_started, prompt_finished),
            elapsed_ms(generation_started, generation_finished),
            output_tokens
        )
    ) {
        return nullptr;
    }
    return bytes_to_java(env, output);
}
