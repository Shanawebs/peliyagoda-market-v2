package lk.peliyagoda.marketpro.cloud

import java.util.UUID

/**
 * Pure UI gating only. Server + Supabase Auth decide real MFA success.
 * No OTP or TOTP seed may be retained in disk, network logs or saved state.
 */
internal object OwnerTotpMfaPolicy {
    private val code=Regex("[0-9]{6}")
    private val secret=Regex("[A-Z2-7]{16,256}")
    fun validOtp(value:String)=code.matches(value)
    fun validFactorId(value:String)=runCatching {
        UUID.fromString(value).toString().equals(value,true)
    }.getOrDefault(false)
    fun validManualSecret(value:String)=secret.matches(value)
    fun canCallAuth(preview:Boolean, ownerMfaDeployed:Boolean,
                    staffLogin:Boolean, pairingDeployed:Boolean):Boolean =
        !preview && ownerMfaDeployed && staffLogin && pairingDeployed
    fun verifiedTotpFactor(factorType:String,status:String,id:String):Boolean =
        factorType=="totp" && status=="verified" && validFactorId(id)
}
