package dev.baseio.slackserver.security

import java.security.spec.MGF1ParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource.PSpecified

/**
 * Contains the constants and enums used by RSA-ECDSA encryption/decryption.
 */
class RsaEcdsaConstants {

    /**
     * Encapsulates the ciphertext padding modes supported by RSA-ECDSA encryption/decryption.
     */
    enum class Padding(private val padding: String) {
        OAEP("OAEPPadding"), PKCS1("PKCS1Padding");

        /**
         * Returns the current padding enum's transformation string that should be used when calling
         * `javax.crypto.Cipher.getInstance`.
         *
         * @return the transformation string.
         */
        val transformation: String
            get() = PREFIX + padding

        companion object {
            private const val PREFIX = "RSA/ECB/"
        }
    }

    companion object {
        val OAEP_PARAMETER_SPEC = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSpecified.DEFAULT)
        const val SIGNATURE_LENGTH_BYTES_LENGTH = 4
        const val FILE_INPUT_STREAM = "ecdsa/sender_signing_key.dat"
    }
}