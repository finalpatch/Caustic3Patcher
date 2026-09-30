package org.caustic.patcher.core;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;

/** Exportable, installation-local identity. Never regenerate an unreadable existing key. */
public final class SigningKeys {
    private static final char[] LOCAL_PASSWORD = "caustic-local-storage".toCharArray();
    public static final class Identity {
        public final PrivateKey key;
        public final X509Certificate certificate;
        public Identity(PrivateKey key, X509Certificate certificate) {
            this.key = key; this.certificate = certificate;
        }
        public String fingerprint() throws Exception { return PatchEngine.sha256(certificate.getEncoded()); }
    }
    public static synchronized Identity loadOrCreate(File file) throws Exception {
        if (file.exists()) return read(new FileInputStream(file), LOCAL_PASSWORD);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        KeyPair pair = generator.generateKeyPair();
        byte[] algorithm = hex("300d06092a864886f70d01010b0500"); // sha256WithRSAEncryption
        byte[] name = der(0x30, der(0x31, der(0x30, concat(hex("0603550403"),
                der(0x0c, "Caustic Patcher local key".getBytes(StandardCharsets.UTF_8))))));
        byte[] validity = der(0x30, concat(der(0x18, "20200101000000Z".getBytes(StandardCharsets.US_ASCII)),
                der(0x18, "21200101000000Z".getBytes(StandardCharsets.US_ASCII))));
        byte[] serial = new BigInteger(159, new SecureRandom()).add(BigInteger.ONE).toByteArray();
        byte[] tbs = der(0x30, concat(der(0x02, serial), algorithm, name, validity, name, pair.getPublic().getEncoded()));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate()); signer.update(tbs);
        byte[] cert = der(0x30, concat(tbs, algorithm, der(0x03, concat(new byte[]{0}, signer.sign()))));
        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(cert));
        certificate.verify(pair.getPublic());
        Identity identity = new Identity(pair.getPrivate(), certificate);
        saveLocal(file, identity);
        return identity;
    }
    public static Identity read(InputStream input, char[] password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream stream = input) { store.load(stream, password); }
        Identity found = null;
        Enumeration<String> aliases = store.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (!store.isKeyEntry(alias)) continue;
            if (found != null) throw new IOException("The backup must contain exactly one signing key.");
            Key key = store.getKey(alias, password);
            if (!(key instanceof PrivateKey) || !(store.getCertificate(alias) instanceof X509Certificate))
                throw new IOException("Not an APK signing key.");
            X509Certificate cert = (X509Certificate) store.getCertificate(alias);
            if (!(cert.getPublicKey() instanceof java.security.interfaces.RSAPublicKey)
                    || ((java.security.interfaces.RSAPublicKey) cert.getPublicKey()).getModulus().bitLength() < 2048)
                throw new IOException("A 2048-bit or stronger RSA signing key is required.");
            cert.checkValidity();
            byte[] challenge = new byte[32]; new SecureRandom().nextBytes(challenge);
            Signature test = Signature.getInstance("SHA256withRSA");
            test.initSign((PrivateKey) key); test.update(challenge); byte[] signed = test.sign();
            test.initVerify(cert); test.update(challenge);
            if (!test.verify(signed)) throw new IOException("Key and certificate do not match.");
            found = new Identity((PrivateKey) key, cert);
        }
        if (found == null) throw new IOException("No signing key in backup.");
        return found;
    }
    public static void write(OutputStream output, Identity identity, char[] password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12"); store.load(null, password);
        store.setKeyEntry("caustic", identity.key, password, new java.security.cert.Certificate[]{identity.certificate});
        store.store(output, password);
    }
    public static synchronized void saveLocal(File file, Identity identity) throws Exception {
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                write(out, identity, LOCAL_PASSWORD); out.getFD().sync();
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { temp.delete(); }
    }
    private static byte[] der(int tag, byte[] value) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(tag);
        int n = value.length;
        if (n < 128) out.write(n);
        else {
            int count = 0; for (int v = n; v != 0; v >>>= 8) count++;
            out.write(0x80 | count);
            for (int i = count - 1; i >= 0; i--) out.write(n >>> (8 * i));
        }
        out.write(value); return out.toByteArray();
    }
    private static byte[] concat(byte[]... arrays) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] array : arrays) out.write(array);
        return out.toByteArray();
    }
    private static byte[] hex(String text) {
        byte[] bytes = new byte[text.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }
}
