package peergos.shared.crypto;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;

import java.util.*;
import java.util.concurrent.*;

public class OwnerProof implements Cborable {
    public final PublicKeyHash ownedKey;
    public final byte[] signedOwner;

    public OwnerProof(PublicKeyHash ownedKey, byte[] signedOwner) {
        this.ownedKey = ownedKey;
        this.signedOwner = signedOwner;
    }

    public CompletableFuture<PublicKeyHash> getOwner(ContentAddressedStorage ipfs) {
        return ipfs.getSigningKey(ownedKey)
                .thenApply(signer -> signer
                        .map(k -> PublicKeyHash.fromCbor(CborObject.fromByteArray(k.unsignMessage(signedOwner))))
                        .orElseThrow(() -> new IllegalStateException("Couldn't retrieve owned key: " + ownedKey)));
    }

    public static OwnerProof build(SigningPrivateKeyAndPublicHash ownedKeypair, PublicKeyHash owner) {
        return new OwnerProof(ownedKeypair.publicKeyHash, ownedKeypair.secret.signMessage(owner.serialize()));
    }

    @Override
    public CborObject toCbor() {
        Map<String, CborObject> result = new TreeMap<>();
        result.put("o", new CborObject.CborMerkleLink(ownedKey));
        result.put("p", new CborObject.CborByteArray(signedOwner));
        return CborObject.CborMap.build(result);
    }

    public static OwnerProof fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for OwnerProof: " + cbor);

        CborObject.CborMap m = (CborObject.CborMap) cbor;
        PublicKeyHash ownedKey = m.get("o", PublicKeyHash::fromCbor);
        byte[] proof = m.get("p", c -> (CborObject.CborByteArray) c).value;
        return new OwnerProof(ownedKey, proof);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OwnerProof that = (OwnerProof) o;
        return Objects.equals(ownedKey, that.ownedKey) &&
                Arrays.equals(signedOwner, that.signedOwner);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(ownedKey);
        result = 31 * result + Arrays.hashCode(signedOwner);
        return result;
    }
}
