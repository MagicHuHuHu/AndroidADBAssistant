package com.adbside.assistant

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

/** 在应用私有目录保存配对身份，避免重启应用后重新配对。 */
class AdbIdentity(context: Context) : AbsAdbConnectionManager() {
    private val key: PrivateKey
    private val cert: Certificate

    init {
        val keyFile = File(context.noBackupFilesDir, "adb.key")
        val certFile = File(context.noBackupFilesDir, "adb.crt")
        if (keyFile.exists() && certFile.exists()) {
            key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            cert = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        } else {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            key = pair.private
            val name = X500Name("CN=ADB Side Assistant")
            val now = System.currentTimeMillis()
            cert = JcaX509CertificateConverter().getCertificate(
                JcaX509v3CertificateBuilder(name, BigInteger(128, SecureRandom()), Date(now - 86400000),
                    Date(now + 10L * 365 * 86400000), name, pair.public)
                    .build(JcaContentSignerBuilder("SHA256withRSA").build(key))
            )
            keyFile.writeBytes(key.encoded)
            certFile.writeBytes(cert.encoded)
        }
        setApi(Build.VERSION.SDK_INT)
        setTimeout(20, TimeUnit.SECONDS)
    }

    /** 返回本机的 ADB 私钥。 */
    override fun getPrivateKey(): PrivateKey = key

    /** 返回无线调试 TLS 证书。 */
    override fun getCertificate(): Certificate = cert

    /** 提供设备 B 授权界面使用的客户端名称。 */
    override fun getDeviceName(): String = "ADBSideAssistant"
}
