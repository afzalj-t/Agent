package org.eclipse.iofog.connector_client;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableEntryException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;

public class ConnectorTruststore {

    public static void createIfRequired(String certContent, String truststoreFileName, String truststorePassword)
        throws KeyStoreException, UnrecoverableEntryException, CertificateException, NoSuchAlgorithmException, IOException {
        boolean needToCreate = false;

        if (Files.exists(Paths.get(truststoreFileName))) {
            InputStream inputStream = new ByteArrayInputStream(certContent.getBytes());
            Certificate cert = getCert(inputStream);

            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            char[] password = truststorePassword.toCharArray();

            try {
                ks.load(Files.newInputStream(Paths.get(truststoreFileName)), password);
                Certificate oldCert = ((KeyStore.TrustedCertificateEntry) ks.getEntry(truststoreFileName, null)).getTrustedCertificate();
                needToCreate = !cert.equals(oldCert);
            } catch (IOException | CertificateException | NoSuchAlgorithmException e) {
                needToCreate = true;
            }
        }

        if (needToCreate) {
            create(certContent, truststoreFileName, truststorePassword);
        }

    }

    private static void create(String certContent, String truststoreFileName, String truststorePassword)
        throws KeyStoreException, CertificateException, NoSuchAlgorithmException, IOException {

        InputStream inputStream = new ByteArrayInputStream(certContent.getBytes());
        Certificate cert = getCert(inputStream);

        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        char[] password = truststorePassword.toCharArray();

        ks.load(null, password);
        KeyStore.TrustedCertificateEntry trustedCertificateEntry = new KeyStore.TrustedCertificateEntry(cert);
        ks.setEntry(truststoreFileName, trustedCertificateEntry, null);

        try (FileOutputStream fos = new FileOutputStream(truststoreFileName)) {
            ks.store(fos, password);
        }

    }

    private static Certificate getCert(InputStream is) {
        Certificate result = null;
        try {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            result = certificateFactory.generateCertificate(is);
        } catch (CertificateException exp) {
            exp.printStackTrace();
        }
        return result;
    }
}
