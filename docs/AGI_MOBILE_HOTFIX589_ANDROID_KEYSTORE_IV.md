# Hotfix589 — Android Keystore Provider-Generated AES-GCM IV

## Physical-device failure

After Hotfix588 passed the Android storage-root boundary, Safe Launcher captured the next
startup failure:

```
java.security.InvalidAlgorithmParameterException:
Caller-provided IV not permitted
```

The stack reached `AesGcmMemoryLineCipher.encrypt()` while persisting the encrypted memory
journal head anchor through an Android Keystore-backed AES-GCM key.

## Root cause

Android Keystore AES keys generated with randomized encryption required reject caller-supplied
IVs during encryption. AMPER previously generated a 96-bit nonce itself and passed it through
`GCMParameterSpec` for both portable JVM keys and Android Keystore keys.

## Fix

Encryption now initializes AES-GCM without a caller-provided IV:

- the selected JCE provider / Android Keystore generates the fresh nonce;
- AMPER reads `cipher.iv` after initialization;
- AMPER requires the provider nonce to be exactly 96 bits;
- the nonce is stored in the existing E1 envelope;
- decryption continues to use the stored nonce through `GCMParameterSpec`.

The E1 format, AAD, key ids, key rotation, and existing encrypted-data read path are unchanged.

## Security

The fix strengthens compatibility with Android Keystore's randomized-encryption contract and
does not disable randomized encryption or permit caller-controlled encryption IVs.
