package SelfSignedCertificate;

import org.bouncycastle.jce.X509Principal;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.x509.X509V3CertificateGenerator;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.Date;


/**
 * This class uses the Bouncycastle lightweight API to generate X.509 certificates programmatically.
 *
 * @author abdul
 */
public class SelfSignedCertificate {

    private static final String CERTIFICATE_ALIAS = "CLIENT-CERTIFICATE";
    private static final String CERTIFICATE_ALGORITHM = "RSA";
    private static final String CERTIFICATE_DN = "CN=cn, O=o, L=L, ST=il, C= c";
    private static final String CERTIFICATE_NAME = "src/assets/certs/clientCert.pem";
    private static final int CERTIFICATE_BITS = 2048;

    static {
        // adds the Bouncy castle provider to java security
        Security.addProvider(new BouncyCastleProvider());
    }

    /**
     * @param args
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {
        SelfSignedCertificate signedCertificate = new SelfSignedCertificate();
        signedCertificate.createCertificate();
    }

    @SuppressWarnings("deprecation")
    public X509Certificate createCertificate() throws Exception {
        X509Certificate cert = null;
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(CERTIFICATE_ALGORITHM);
        keyPairGenerator.initialize(CERTIFICATE_BITS, new SecureRandom());
        KeyPair keyPair = keyPairGenerator.generateKeyPair();

        // GENERATE THE X509 CERTIFICATE
        X509V3CertificateGenerator v3CertGen = new X509V3CertificateGenerator();
        v3CertGen.setSerialNumber(BigInteger.valueOf(System.currentTimeMillis()));
        v3CertGen.setIssuerDN(new X509Principal(CERTIFICATE_DN));
        v3CertGen.setNotBefore(new Date(System.currentTimeMillis() - 1000L * 60 * 60 * 24));
        v3CertGen.setNotAfter(new Date(System.currentTimeMillis() + (1000L * 60 * 60 * 24 * 365 * 10)));
        v3CertGen.setSubjectDN(new X509Principal(CERTIFICATE_DN));
        v3CertGen.setPublicKey(keyPair.getPublic());
        v3CertGen.setSignatureAlgorithm("SHA256WithRSAEncryption");
        cert = v3CertGen.generateX509Certificate(keyPair.getPrivate());
        saveCert(cert, keyPair.getPrivate());
        return cert;
    }

    private void saveCert(X509Certificate cert, PrivateKey key) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        //change key store password if considered necessary
        keyStore.load(new FileInputStream("src/assets/keyStores/trustCertsServerKeyStore.p12"), "123456".toCharArray());
        keyStore.setKeyEntry(CERTIFICATE_ALIAS, key, "".toCharArray(), new java.security.cert.Certificate[]{cert});
        //File file = new File(".", CERTIFICATE_NAME);
        //change key store password if considered necessary
        keyStore.store(new FileOutputStream("src/assets/keyStores/trustCertsServerKeyStore.p12"), "123456".toCharArray());
    }
}