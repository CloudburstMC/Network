package org.cloudburstmc.netty.util.nethernet;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.util.Arrays;
import java.util.HexFormat;

/** Canonical named secp384r1 SPKI DER, with an uncompressed and validated public point. */
public final class IdentityPublicKey {
    private static final ECParameterSpec CURVE;
    private static final byte[] PREFIX =
            HexFormat.of().parseHex("3076301006072a8648ce3d020106052b8104002203620004");

    static {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp384r1"));
            CURVE = parameters.getParameterSpec(ECParameterSpec.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private IdentityPublicKey() {
    }

    /**
     * @param key The key to canonicalise
     * @return Its fixed 120 byte encoding, so that two spellings of one key compare equal
     * @throws IllegalArgumentException If it is not a point on secp384r1
     */
    public static byte[] canonical(PublicKey key) {
        if (!(key instanceof ECPublicKey ec)) {
            throw new IllegalArgumentException("Expected an EC public key");
        }
        ECParameterSpec params = ec.getParams();
        if (params == null || !CURVE.getCurve().equals(params.getCurve())
                || !CURVE.getGenerator().equals(params.getGenerator())
                || !CURVE.getOrder().equals(params.getOrder()) || CURVE.getCofactor() != params.getCofactor()) {
            throw new IllegalArgumentException("Expected secp384r1");
        }
        BigInteger x = ec.getW().getAffineX(), y = ec.getW().getAffineY();
        BigInteger p = ((ECFieldFp) CURVE.getCurve().getField()).getP();
        if (x == null || y == null || x.signum() < 0 || y.signum() < 0 || x.compareTo(p) >= 0 || y.compareTo(p) >= 0
                || !y.multiply(y).mod(p).equals(x.pow(3).add(CURVE.getCurve().getA().multiply(x))
                .add(CURVE.getCurve().getB()).mod(p))) {
            throw new IllegalArgumentException("Invalid secp384r1 point");
        }
        byte[] result = Arrays.copyOf(PREFIX, 120);
        coordinate(x, result, PREFIX.length);
        coordinate(y, result, PREFIX.length + 48);
        return result;
    }

    private static void coordinate(BigInteger value, byte[] target, int offset) {
        byte[] bytes = value.toByteArray();
        int length = Math.min(48, bytes.length);
        System.arraycopy(bytes, bytes.length - length, target, offset + 48 - length, length);
    }
}
