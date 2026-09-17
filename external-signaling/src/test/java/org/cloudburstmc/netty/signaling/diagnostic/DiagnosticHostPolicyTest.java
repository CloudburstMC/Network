package org.cloudburstmc.netty.signaling.diagnostic;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmissionCodec.*;
class DiagnosticHostPolicyTest {
    final Context context=new Context("https://provider.example","host","01".repeat(16),1);
    static DiagnosticHostPolicy.Endpoint endpoint(int port) {return new DiagnosticHostPolicy.Endpoint(4,"00".repeat(12)+"7f000001",port,7);}
    @Test void ownsBoundedConfigurationAndIndependentEndpointDeadlines() {
        var endpoints=new HashSet<DiagnosticHostPolicy.Endpoint>(); var deadlines=new HashMap<DiagnosticHostPolicy.Endpoint,Long>();
        for(int i=0;i<32;i++){var endpoint=endpoint(20000+i);endpoints.add(endpoint);deadlines.put(endpoint,2000L+i);}
        var keys=new ArrayList<Key>();for(int i=0;i<8;i++)keys.add(new Key("D00"+i,"s".repeat(32),0,10000));
        var policy=new DiagnosticHostPolicy(context,keys,endpoints,4000,deadlines); endpoints.clear();keys.clear();deadlines.clear();
        assertEquals(32,policy.endpoints().size());assertEquals(8,policy.keys().size());assertEquals(2031,policy.endpointExpiry(endpoint(20031)));
        assertThrows(UnsupportedOperationException.class,()->policy.endpointExpiries().clear());
        var tooMany=new HashSet<>(policy.endpoints());tooMany.add(endpoint(30000));
        assertThrows(IllegalArgumentException.class,()->new DiagnosticHostPolicy(context,policy.keys(),tooMany,4000));
        var extra=new ArrayList<>(policy.keys());extra.add(new Key("D008","s".repeat(32),0,10000));
        assertThrows(IllegalArgumentException.class,()->new DiagnosticHostPolicy(context,extra,policy.endpoints(),4000));
    }
    @Test void twoAssistedFamiliesDoNotConsumeOrExpandConcreteEndpointCapacity() {
        var endpoints=new HashSet<DiagnosticHostPolicy.Endpoint>();
        for(int i=0;i<32;i++)endpoints.add(endpoint(20000+i));
        endpoints.add(DiagnosticHostPolicy.Endpoint.assisted(4,7));endpoints.add(DiagnosticHostPolicy.Endpoint.assisted(6,7));
        var policy=new DiagnosticHostPolicy(context,List.of(),endpoints,4000);
        assertEquals(34,policy.endpoints().size());
        endpoints.remove(endpoint(20000));endpoints.add(DiagnosticHostPolicy.Endpoint.assisted(4,8));
        assertThrows(IllegalArgumentException.class,()->new DiagnosticHostPolicy(context,List.of(),endpoints,4000));
    }
    @Test void rejectsAmbiguousEndpointsDuplicateKeysAndInvalidDeadlines() {
        var target=endpoint(19132);var key=new Key("D001","secret-value-which-must-not-be-logged",0,5000);
        assertThrows(IllegalArgumentException.class,()->new DiagnosticHostPolicy(context,List.of(key,key),Set.of(target),4000));
        assertThrows(IllegalArgumentException.class,()->new DiagnosticHostPolicy(context,List.of(key),Set.of(target),4000,Map.of()));
        assertThrows(IllegalArgumentException.class,()->new DiagnosticHostPolicy(context,List.of(key),Set.of(target),4000,Map.of(target,4001L)));
        assertThrows(IllegalArgumentException.class,()->new DiagnosticHostPolicy.Endpoint(6,"00000000000000000000ffff7f000001",19132,1));
        assertFalse(new DiagnosticHostPolicy(context,List.of(key),Set.of(target),4000).toString().contains("secret-value"));
    }
}
