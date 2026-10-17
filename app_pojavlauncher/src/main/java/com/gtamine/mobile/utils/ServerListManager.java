package com.gtamine.mobile.utils;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Maintains the pinned GTAMINE entry in Minecraft's servers.dat file. */
public final class ServerListManager {
    private static final String TAG = "GTAMINE-Servers";
    private static final int TAG_END = 0;
    private static final int TAG_BYTE = 1;
    private static final int TAG_SHORT = 2;
    private static final int TAG_INT = 3;
    private static final int TAG_LONG = 4;
    private static final int TAG_FLOAT = 5;
    private static final int TAG_DOUBLE = 6;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_LIST = 9;
    private static final int TAG_COMPOUND = 10;
    private static final int TAG_INT_ARRAY = 11;
    private static final int TAG_LONG_ARRAY = 12;
    private static final int MAX_COLLECTION_SIZE = 1_000_000;

    private ServerListManager() {}

    public static boolean ensureServer(File gameDirectory, String displayName, String address) {
        File serverFile = new File(gameDirectory, "servers.dat");
        CompoundTag root;
        try {
            root = serverFile.isFile() ? readRoot(serverFile) : new CompoundTag();
        } catch (IOException | RuntimeException exception) {
            Log.w(TAG, "Could not read servers.dat; preserving it as a backup", exception);
            root = new CompoundTag();
        }

        ListTag servers = root.getList("servers", TAG_COMPOUND);
        if (servers == null) {
            servers = new ListTag(TAG_COMPOUND);
            root.values.put("servers", new Tag(TAG_LIST, servers));
        }

        CompoundTag gtamine = null;
        for (Object item : servers.values) {
            if (!(item instanceof CompoundTag)) continue;
            String savedAddress = ((CompoundTag) item).getString("ip");
            if (normalizeAddress(address).equals(normalizeAddress(savedAddress))) {
                gtamine = (CompoundTag) item;
                break;
            }
        }

        if (gtamine == null) {
            gtamine = new CompoundTag();
            servers.values.add(gtamine);
        }
        // Quick Play may create a hidden entry for the same address. Always
        // rewrite it as a regular visible favorite instead of returning early.
        gtamine.values.put("name", new Tag(TAG_STRING, displayName));
        gtamine.values.put("ip", new Tag(TAG_STRING, address));
        gtamine.values.put("hidden", new Tag(TAG_BYTE, (byte) 0));
        gtamine.values.put("acceptTextures", new Tag(TAG_BYTE, (byte) 1));

        try {
            writeRootSafely(serverFile, root);
            Log.i(TAG, "Pinned server in " + serverFile.getAbsolutePath());
            return true;
        } catch (IOException exception) {
            Log.e(TAG, "Could not add the GTAMINE server", exception);
            return false;
        }
    }

    private static String normalizeAddress(String address) {
        if (address == null) return "";
        String normalized = address.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.endsWith(":25565")
                ? normalized.substring(0, normalized.length() - 6)
                : normalized;
    }

    private static CompoundTag readRoot(File file) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(new FileInputStream(file));
        buffered.mark(2);
        int first = buffered.read();
        int second = buffered.read();
        buffered.reset();
        InputStream payload = first == 0x1f && second == 0x8b
                ? new GZIPInputStream(buffered)
                : buffered;
        try (DataInputStream input = new DataInputStream(payload)) {
            int type = input.readUnsignedByte();
            if (type != TAG_COMPOUND) throw new IOException("Invalid servers.dat root tag: " + type);
            input.readUTF();
            return (CompoundTag) readPayload(input, TAG_COMPOUND);
        }
    }

    private static void writeRootSafely(File file, CompoundTag root) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create game directory");
        }
        File temporary = new File(parent, "servers.dat.gtamine.tmp");
        File backup = new File(parent, "servers.dat.gtamine.bak");
        // Minecraft writes servers.dat as raw, uncompressed NBT. Reading also
        // accepts the GZIP file produced by older GTAMINE builds so it can be
        // repaired automatically on the next launch.
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                new FileOutputStream(temporary)))) {
            output.writeByte(TAG_COMPOUND);
            output.writeUTF("");
            writePayload(output, TAG_COMPOUND, root);
        }

        boolean hadOriginal = file.exists();
        if (backup.exists() && !backup.delete()) throw new IOException("Could not replace backup");
        if (hadOriginal && !file.renameTo(backup)) throw new IOException("Could not back up servers.dat");
        if (!temporary.renameTo(file)) {
            if (hadOriginal) backup.renameTo(file);
            throw new IOException("Could not install servers.dat");
        }
    }

    private static Object readPayload(DataInputStream input, int type) throws IOException {
        switch (type) {
            case TAG_BYTE: return input.readByte();
            case TAG_SHORT: return input.readShort();
            case TAG_INT: return input.readInt();
            case TAG_LONG: return input.readLong();
            case TAG_FLOAT: return input.readFloat();
            case TAG_DOUBLE: return input.readDouble();
            case TAG_BYTE_ARRAY: {
                int length = checkedLength(input.readInt());
                byte[] value = new byte[length];
                input.readFully(value);
                return value;
            }
            case TAG_STRING: return input.readUTF();
            case TAG_LIST: {
                int elementType = input.readUnsignedByte();
                int length = checkedLength(input.readInt());
                ListTag list = new ListTag(elementType);
                for (int i = 0; i < length; i++) list.values.add(readPayload(input, elementType));
                return list;
            }
            case TAG_COMPOUND: {
                CompoundTag compound = new CompoundTag();
                while (true) {
                    int childType;
                    try {
                        childType = input.readUnsignedByte();
                    } catch (EOFException exception) {
                        throw new IOException("Unexpected end of NBT compound", exception);
                    }
                    if (childType == TAG_END) return compound;
                    String name = input.readUTF();
                    compound.values.put(name, new Tag(childType, readPayload(input, childType)));
                }
            }
            case TAG_INT_ARRAY: {
                int length = checkedLength(input.readInt());
                int[] value = new int[length];
                for (int i = 0; i < length; i++) value[i] = input.readInt();
                return value;
            }
            case TAG_LONG_ARRAY: {
                int length = checkedLength(input.readInt());
                long[] value = new long[length];
                for (int i = 0; i < length; i++) value[i] = input.readLong();
                return value;
            }
            default: throw new IOException("Unsupported NBT tag type: " + type);
        }
    }

    private static void writePayload(DataOutputStream output, int type, Object value) throws IOException {
        switch (type) {
            case TAG_BYTE: output.writeByte((Byte) value); break;
            case TAG_SHORT: output.writeShort((Short) value); break;
            case TAG_INT: output.writeInt((Integer) value); break;
            case TAG_LONG: output.writeLong((Long) value); break;
            case TAG_FLOAT: output.writeFloat((Float) value); break;
            case TAG_DOUBLE: output.writeDouble((Double) value); break;
            case TAG_BYTE_ARRAY: {
                byte[] bytes = (byte[]) value;
                output.writeInt(bytes.length);
                output.write(bytes);
                break;
            }
            case TAG_STRING: output.writeUTF((String) value); break;
            case TAG_LIST: {
                ListTag list = (ListTag) value;
                output.writeByte(list.elementType);
                output.writeInt(list.values.size());
                for (Object item : list.values) writePayload(output, list.elementType, item);
                break;
            }
            case TAG_COMPOUND: {
                CompoundTag compound = (CompoundTag) value;
                for (Map.Entry<String, Tag> entry : compound.values.entrySet()) {
                    output.writeByte(entry.getValue().type);
                    output.writeUTF(entry.getKey());
                    writePayload(output, entry.getValue().type, entry.getValue().value);
                }
                output.writeByte(TAG_END);
                break;
            }
            case TAG_INT_ARRAY: {
                int[] ints = (int[]) value;
                output.writeInt(ints.length);
                for (int item : ints) output.writeInt(item);
                break;
            }
            case TAG_LONG_ARRAY: {
                long[] longs = (long[]) value;
                output.writeInt(longs.length);
                for (long item : longs) output.writeLong(item);
                break;
            }
            default: throw new IOException("Unsupported NBT tag type: " + type);
        }
    }

    private static int checkedLength(int length) throws IOException {
        if (length < 0 || length > MAX_COLLECTION_SIZE) {
            throw new IOException("Invalid NBT collection length: " + length);
        }
        return length;
    }

    private static final class Tag {
        final int type;
        final Object value;

        Tag(int type, Object value) {
            this.type = type;
            this.value = value;
        }
    }

    private static final class CompoundTag {
        final Map<String, Tag> values = new LinkedHashMap<>();

        String getString(String name) {
            Tag tag = values.get(name);
            return tag != null && tag.type == TAG_STRING ? (String) tag.value : null;
        }

        ListTag getList(String name, int elementType) {
            Tag tag = values.get(name);
            if (tag == null || tag.type != TAG_LIST) return null;
            ListTag list = (ListTag) tag.value;
            return list.elementType == elementType ? list : null;
        }
    }

    private static final class ListTag {
        final int elementType;
        final List<Object> values = new ArrayList<>();

        ListTag(int elementType) {
            this.elementType = elementType;
        }
    }
}
