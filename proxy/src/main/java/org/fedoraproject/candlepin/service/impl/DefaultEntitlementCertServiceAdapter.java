/**
 * Copyright (c) 2009 Red Hat, Inc.
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package org.fedoraproject.candlepin.service.impl;

import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.log4j.Logger;
import org.fedoraproject.candlepin.model.CertificateSerialCurator;
import org.fedoraproject.candlepin.model.Consumer;
import org.fedoraproject.candlepin.model.Entitlement;
import org.fedoraproject.candlepin.model.EntitlementCertificate;
import org.fedoraproject.candlepin.model.EntitlementCertificateCurator;
import org.fedoraproject.candlepin.model.KeyPairCurator;
import org.fedoraproject.candlepin.model.Product;
import org.fedoraproject.candlepin.model.Subscription;
import org.fedoraproject.candlepin.pki.PKIUtility;
import org.fedoraproject.candlepin.pki.X509ExtensionWrapper;
import org.fedoraproject.candlepin.service.BaseEntitlementCertServiceAdapter;
import org.fedoraproject.candlepin.util.X509ExtensionUtil;

import com.google.inject.Inject;

/**
 * DefaultEntitlementCertServiceAdapter
 */
public class DefaultEntitlementCertServiceAdapter extends 
    BaseEntitlementCertServiceAdapter {
    
    private PKIUtility pki;
    private X509ExtensionUtil extensionUtil;
    private KeyPairCurator keyPairCurator;
    private CertificateSerialCurator serialCurator;
    
    private static Logger log = Logger
        .getLogger(DefaultEntitlementCertServiceAdapter.class);
    
    @Inject
    public DefaultEntitlementCertServiceAdapter(PKIUtility pki,
        X509ExtensionUtil extensionUtil,
        EntitlementCertificateCurator entCertCurator, 
        KeyPairCurator keyPairCurator,
        CertificateSerialCurator serialCurator) {
        
        this.pki = pki;
        this.extensionUtil = extensionUtil;
        this.entCertCurator = entCertCurator;
        this.keyPairCurator = keyPairCurator;
        this.serialCurator = serialCurator;
    }

    
    // NOTE: we use entitlement here, but it version does not...
    // NOTE: we can get consumer from entitlement.getConsumer()
    @Override
    public EntitlementCertificate generateEntitlementCert(Consumer consumer,
        Entitlement entitlement, Subscription sub, Product product, Date endDate)
        throws GeneralSecurityException, IOException {
        
        log.debug("Generating entitlement cert for:");
        log.debug("   consumer: " + consumer.getUuid());
        log.debug("   product: " + product.getId());
        log.debug("   end date: " + endDate);
        
        
        KeyPair keyPair = keyPairCurator.getConsumerKeyPair(consumer);
        BigInteger serialNumber = new BigInteger(serialCurator.getNextSerial().toString());
        
        X509Certificate x509Cert = createX509Certificate(consumer, entitlement, sub,
            product, endDate, serialNumber, keyPair);
        
        EntitlementCertificate cert = new EntitlementCertificate();
        cert.setSerial(serialNumber);
        cert.setKeyAsBytes(pki.getPemEncoded(keyPair.getPrivate()));
        cert.setCertAsBytes(this.pki.getPemEncoded(x509Cert));
        cert.setEntitlement(entitlement);
        
        log.debug("Generated cert serial number: " + serialNumber);
        log.debug("Key: " + cert.getKey());
        log.debug("Cert: " + cert.getCert());
        
        entitlement.getCertificates().add(cert);
        entCertCurator.create(cert);
        return cert;
    }
    
    @Override
    public void revokeEntitlementCertificates(Entitlement e) {
        // TODO: delete certs; store their serial numbers; potentially generate crls
        // TODO: update cascading on Entitlement.certificates
    }

    public X509Certificate createX509Certificate(Consumer consumer,
        Entitlement ent, Subscription sub, Product product, Date endDate, 
        BigInteger serialNumber, KeyPair keyPair) 
        throws GeneralSecurityException, IOException {
        // oiduitl is busted at the moment, so do this manually
        Set<X509ExtensionWrapper> extensions = new LinkedHashSet<X509ExtensionWrapper>();
        
        addExtensionsForProduct(extensions, product);
        for (Product provided : sub.getProvidedProducts()) {
            addExtensionsForProduct(extensions, provided);
        }

        extensions.addAll(extensionUtil.subscriptionExtensions(sub));
        extensions.addAll(extensionUtil.entitlementExtensions(ent));
        extensions.addAll(extensionUtil.consumerExtensions(consumer));
        
        X509Certificate x509Cert = this.pki.createX509Certificate(createDN(consumer), 
            extensions, sub.getStartDate(), endDate, keyPair, serialNumber);
        return x509Cert;
    }
    
    /**
     * Recursively add certificate extensions for this product, and all it's children.
     * @param extensions Certificate extensions.
     * @param product Product to recurse through.
     */
    private void addExtensionsForProduct(Set<X509ExtensionWrapper> extensions, 
        Product product) {
        
        // Add extensions for this product, unless it is a MKT product,
        // then we just want the childProducts
        if (!product.getAttributeValue("type").equals("MKT")) {
            extensions.addAll(extensionUtil.productExtensions(product));
            extensions.addAll(extensionUtil.contentExtensions(product));
        }
    }
    
    private String createDN(Consumer consumer) {
        StringBuilder sb = new StringBuilder("CN=");
        sb.append(consumer.getName());
        sb.append(", UID=");
        sb.append(consumer.getUuid());
        return sb.toString();
    }

}
