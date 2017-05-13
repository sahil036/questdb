package com.questdb.txt;

import com.questdb.BootstrapEnv;
import com.questdb.json.JsonException;
import com.questdb.json.JsonLexer;
import com.questdb.json.JsonListener;
import com.questdb.misc.Chars;
import com.questdb.misc.Unsafe;
import com.questdb.std.CharSequenceIntHashMap;
import com.questdb.std.Mutable;
import com.questdb.std.ObjList;
import com.questdb.std.ObjectPool;
import com.questdb.std.str.AbstractCharSequence;
import com.questdb.std.str.ByteSequence;
import com.questdb.std.time.DateFormat;
import com.questdb.std.time.DateFormatFactory;
import com.questdb.std.time.DateLocale;
import com.questdb.std.time.DateLocaleFactory;
import com.questdb.store.ColumnType;

public class SchemaParser implements JsonListener, Mutable {
    private static final int S_NEED_ARRAY = 1;
    private static final int S_NEED_OBJECT = 2;
    private static final int S_NEED_PROPERTY = 3;
    private static final int P_NAME = 1;
    private static final int P_TYPE = 2;
    private static final int P_PATTERN = 3;
    private static final int P_LOCALE = 4;
    private static final CharSequenceIntHashMap propertyNameMap = new CharSequenceIntHashMap();
    private final ObjectPool<ImportedColumnMetadata> mPool = new ObjectPool<>(ImportedColumnMetadata.FACTORY, 64);
    private final DateLocaleFactory dateLocaleFactory;
    private final ObjList<ImportedColumnMetadata> metadata = new ObjList<>();
    private final ObjectPool<FloatingCharSequence> csPool = new ObjectPool<>(FloatingCharSequence::new, 64);
    private final DateFormatFactory dateFormatFactory;
    private int state = S_NEED_ARRAY;
    private CharSequence name;
    private int type = -1;
    private CharSequence pattern;
    private DateFormat dateFormat;
    private DateLocale dateLocale;
    private int propertyIndex;
    private long buf;
    private long bufCapacity = 0;
    private int bufSize = 0;

    public SchemaParser(BootstrapEnv env) {
        this.dateLocaleFactory = env.dateLocaleFactory;
        this.dateFormatFactory = env.dateFormatFactory;
    }

    @Override
    public void clear() {
        bufSize = 0;
        state = S_NEED_ARRAY;
        metadata.clear();
        csPool.clear();
        mPool.clear();
        clearStage();
    }

    public ObjList<ImportedColumnMetadata> getMetadata() {
        return metadata;
    }

    @Override
    public void onEvent(int code, ByteSequence tag, int position) throws JsonException {
        switch (code) {
            case JsonLexer.EVT_ARRAY_START:
                if (state != S_NEED_ARRAY) {
                    throw JsonException.with("Unexpected array", position);
                }
                state = S_NEED_OBJECT;
                break;
            case JsonLexer.EVT_OBJ_START:
                if (state != S_NEED_OBJECT) {
                    throw JsonException.with("Unexpected object", position);
                }
                state = S_NEED_PROPERTY;
                break;
            case JsonLexer.EVT_NAME:
                this.propertyIndex = propertyNameMap.get(tag);
                break;
            case JsonLexer.EVT_VALUE:
                switch (propertyIndex) {
                    case P_NAME:
                        name = copy(tag);
                        break;
                    case P_TYPE:
                        type = ColumnType.columnTypeOf(tag);
                        if (type == -1) {
                            throw JsonException.with("Invalid type", position);
                        }
                        break;
                    case P_PATTERN:
                        dateFormat = dateFormatFactory.get(tag);
                        pattern = copy(tag);
                        break;
                    case P_LOCALE:
                        dateLocale = dateLocaleFactory.getDateLocale(tag);
                        if (dateLocale == null) {
                            throw JsonException.with("Invalid date locale", position);
                        }
                        break;
                    default:
                        break;
                }
                break;
            case JsonLexer.EVT_OBJ_END:
                state = S_NEED_OBJECT;
                createImportedType(position);
                break;
            case JsonLexer.EVT_ARRAY_VALUE:
                throw JsonException.with("Must be an object", position);
            default:
                break;
        }
    }

    private void clearStage() {
        name = null;
        pattern = null;
        type = -1;
        dateLocale = null;
        dateFormat = null;
    }

    private CharSequence copy(CharSequence tag) {
        int l = tag.length();
        long n = bufSize + l;
        if (n >= bufCapacity) {
            long ptr = Unsafe.malloc(n * 2);
            Unsafe.getUnsafe().copyMemory(buf, ptr, bufSize);
            Unsafe.free(buf, bufCapacity);
            buf = ptr;
            bufCapacity = n * 2;
        }

        Chars.strcpy(tag, l, buf + bufSize);
        CharSequence cs = csPool.next().of(bufSize, bufSize + l);
        bufSize += l;
        return cs;
    }

    private void createImportedType(int position) throws JsonException {
        if (name == null) {
            throw JsonException.with("Missing 'name' property", position);
        }

        if (type == -1) {
            throw JsonException.with("Missing 'type' property", position);
        }

        ImportedColumnMetadata m = mPool.next();
        m.name = name;
        m.importedColumnType = type;
        m.pattern = pattern;
        m.dateFormat = dateFormat;
        m.dateLocale = dateLocale == null && type == ColumnType.DATE ? dateLocaleFactory.getDefaultDateLocale() : dateLocale;
        metadata.add(m);

        // prepare for next iteration
        clearStage();
    }

    private class FloatingCharSequence extends AbstractCharSequence implements Mutable {

        int lo;
        int hi;

        @Override
        public int length() {
            return hi - lo;
        }

        @Override
        public char charAt(int index) {
            return (char) Unsafe.getUnsafe().getByte(buf + lo + index);
        }

        CharSequence of(int lo, int hi) {
            this.lo = lo;
            this.hi = hi;
            return this;
        }

        @Override
        public void clear() {
        }
    }

    static {
        propertyNameMap.put("name", P_NAME);
        propertyNameMap.put("type", P_TYPE);
        propertyNameMap.put("pattern", P_PATTERN);
        propertyNameMap.put("locale", P_LOCALE);
    }
}
