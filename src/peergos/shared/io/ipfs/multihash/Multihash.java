package peergos.shared.io.ipfs.multihash;

import jsinterop.annotations.JsConstructor;
import jsinterop.annotations.JsType;
import peergos.shared.io.ipfs.multibase.*;

import java.io.*;
import java.util.*;

@JsType
public class Multihash implements Comparable<Multihash> {
    @JsType
    public enum Type {
        id(0, -1),
        sha1(0x11, 20),
        sha2_256(0x12, 32),
        sha2_512(0x13, 64),
        sha3(0x14, 64),
        blake2b(0x40, 64),
        blake2s(0x41, 32);

        public final int index, length;

        Type(int index, int length) {
            this.index = index;
            this.length = length;
        }

        private static Map<Integer, Type> lookup = new TreeMap<>();
        static {
            for (Type t: Type.values())
                lookup.put(t.index, t);
        }

        public static Type lookup(int t) {
            if (!lookup.containsKey(t))
                throw new IllegalStateException("Unknown Multihash type: "+t);
            return lookup.get(t);
        }
    }

    public final Type type;
    private final byte[] hash;

    @JsConstructor
    public Multihash(Type type, byte[] hash) {
        if (hash.length > 127)
            throw new IllegalStateException("Unsupported hash size: "+hash.length);
        if (hash.length != type.length && type != Type.id)
            throw new IllegalStateException("Incorrect hash length: " + hash.length + " != "+type.length);
        this.type = type;
        this.hash = hash;
    }

    public static Multihash decode(byte[] multihash) {
        return new Multihash(Type.lookup(multihash[0] & 0xff), Arrays.copyOfRange(multihash, 2, multihash.length));
    }
    @Override
    public int compareTo(Multihash that) {
        int compare = Integer.compare(this.hash.length, that.hash.length);
        if (compare != 0)
            return compare;
        for (int i = 0; i < this.hash.length; i++) {
            compare = Byte.compare(this.hash[i], that.hash[i]);
            if (compare != 0)
                return compare;
        }
        return Integer.compare(type.index, that.type.index);
    }

    public byte[] toBytes() {
        byte[] res = new byte[hash.length+2];
        res[0] = (byte)type.index;
        res[1] = (byte)hash.length;
        System.arraycopy(hash, 0, res, 2, hash.length);
        return res;
    }

    public byte[] getHash() {
        return Arrays.copyOfRange(hash, 0, hash.length);
    }

    @SuppressWarnings("unusable-by-js")
    public void serialize(DataOutput dout) throws IOException {
        dout.write(toBytes());
    }

    @SuppressWarnings("unusable-by-js")
    public static Multihash deserialize(DataInput din) throws IOException {
        int type = din.readUnsignedByte();
        int len = din.readUnsignedByte();
        Type t = Type.lookup(type);
        byte[] hash = new byte[len];
        din.readFully(hash);
        return new Multihash(t, hash);
    }

    @Override
    public String toString() {
        return toBase58();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Multihash))
            return false;
        return type == ((Multihash) o).type && Arrays.equals(hash, ((Multihash) o).hash);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(hash) ^ type.hashCode();
    }

    public String toBase58() {
        return Base58.encode(toBytes());
    }

    public static Multihash fromBase58(String base58) {
        return Multihash.decode(Base58.decode(base58));
    }
}
