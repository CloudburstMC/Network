package org.cloudburstmc.netty.signaling.diagnostic;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmissionCodec.*;

class DiagnosticAdmissionTest {
    static DiagnosticAdmission.Binding binding() {
        return new DiagnosticAdmission.Binding(new Context("https://provider.example", "test-host", "01".repeat(16), 1),
                "authority_fixture_001", 1, "profile_fixture_001", "A".repeat(43), 1, "A".repeat(43), "00".repeat(32));
    }
    static DiagnosticAdmission.Endpoint endpoint(int port, long expiry) {
        return new DiagnosticAdmission.Endpoint(new DiagnosticHostPolicy.Endpoint(4, "00".repeat(12) + "7f000001", port, 7), "host", expiry);
    }
    @Test void explicitlyOwnsThirtyTwoEndpointsAndEightKeysWithoutTruncation() {
        var endpoints = new ArrayList<DiagnosticAdmission.Endpoint>(); for (int i=0;i<32;i++) endpoints.add(endpoint(20000+i, 2000+i));
        var keys = new ArrayList<Key>(); for (int i=0;i<8;i++) keys.add(new Key("D00"+i,"s".repeat(32),0,10000));
        var policy = new DiagnosticAdmission.Policy(binding(), keys, endpoints, 1000, 4000);
        endpoints.clear(); keys.clear();
        assertEquals(32,policy.endpoints().size()); assertEquals(32,policy.hostPolicy().endpoints().size()); assertEquals(8,policy.keys().size());
        assertEquals(2000,policy.hostPolicy().endpointExpiry(endpoint(20000,2000).target()));
        assertEquals(2031,policy.hostPolicy().endpointExpiry(endpoint(20031,2031).target()));
        assertThrows(UnsupportedOperationException.class,()->policy.hostPolicy().endpointExpiries().clear());
        var tooMany = new ArrayList<>(policy.endpoints()); tooMany.add(endpoint(30000,3000));
        assertThrows(IllegalArgumentException.class,()->new DiagnosticAdmission.Policy(binding(),policy.keys(),tooMany,1000,4000));
        var tooManyKeys = new ArrayList<>(policy.keys()); tooManyKeys.add(new Key("D008","s".repeat(32),0,10000));
        assertThrows(IllegalArgumentException.class,()->new DiagnosticAdmission.Policy(binding(),tooManyKeys,policy.endpoints(),1000,4000));
    }
    @Test void endpointIntervalsRemainIndependentAndClosed() {
        var four=endpoint(19132,2000);
        var six=new DiagnosticAdmission.Endpoint(new DiagnosticHostPolicy.Endpoint(6,"00".repeat(15)+"01",19132,8),"srflx",4000);
        var p=new DiagnosticAdmission.Policy(binding(),List.of(),List.of(four,six),1000,5000);
        assertEquals(5000,p.hostPolicy().expiresAt()); assertEquals(2000,p.hostPolicy().endpointExpiry(four.target()));
        assertEquals(4000,p.hostPolicy().endpointExpiry(six.target()));
        assertThrows(IllegalArgumentException.class,()->new DiagnosticAdmission.Policy(binding(),List.of(),List.of(four,endpoint(19132,3000)),1000,5000));
        assertThrows(IllegalArgumentException.class,()->new DiagnosticAdmission.Policy(binding(),List.of(),List.of(endpoint(19132,6000)),1000,5000));
        assertThrows(IllegalArgumentException.class,()->new DiagnosticAdmission.Policy(binding(),List.of(),List.of(),1000,301001));
        assertThrows(IllegalArgumentException.class,()->new DiagnosticAdmission.Endpoint(four.target(),"relay",2000));
    }
    @Test void acceptsTheSharedControlIdentifierAlphabetForAuthorityAndProfile() {
        var b = binding();
        for (String identifier : List.of("a", "authority:one", "hpr.v2-1", "a".repeat(128))) {
            var value = new DiagnosticAdmission.Binding(b.context(), identifier, 1, identifier,
                b.hostProfileSha256(), 1, b.installationSha256(), b.hostFingerprintHex());
            assertEquals(identifier, value.authorityIncarnation());
            assertEquals(identifier, value.hostProfileRevision());
        }
        for (String identifier : List.of("", "_invalid", "has space", "a".repeat(129))) {
            assertThrows(IllegalArgumentException.class, () -> new DiagnosticAdmission.Binding(b.context(), identifier, 1,
                b.hostProfileRevision(), b.hostProfileSha256(), 1, b.installationSha256(), b.hostFingerprintHex()));
        }
    }

    @Test void bindingRejectsAmbiguousDigestAndInstallationOutputRedactsSecrets() {
        var b=binding();
        assertThrows(IllegalArgumentException.class,()->new DiagnosticAdmission.Binding(b.context(),b.authorityIncarnation(),1,b.hostProfileRevision(),
                "A".repeat(42)+"B",1,b.installationSha256(),b.hostFingerprintHex()));
        var p=new DiagnosticAdmission.Policy(b,List.of(new Key("D001","secret-value-which-must-not-be-logged",0,2000)),List.of(endpoint(19132,1900)),1000,2000);
        assertFalse(p.toString().contains("secret-value")); assertFalse(p.hostPolicy().toString().contains("secret-value"));
    }
}
