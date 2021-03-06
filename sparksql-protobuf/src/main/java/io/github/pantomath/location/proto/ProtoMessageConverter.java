/**
 * The MIT License
 * Copyright © 2022 Project Location Service using GRPC and IP lookup
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.github.pantomath.location.proto;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import com.twitter.elephantbird.util.Protobufs;
import org.apache.parquet.column.Dictionary;
import org.apache.parquet.io.InvalidRecordException;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.io.api.Converter;
import org.apache.parquet.io.api.GroupConverter;
import org.apache.parquet.io.api.PrimitiveConverter;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.IncompatibleSchemaModificationException;
import org.apache.parquet.schema.Type;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.protobuf.Descriptors.FieldDescriptor.JavaType;

/**
 * Converts Protocol Buffer message (both top level and inner) to parquet.
 * This is internal class, use {@link ProtoRecordConverter}.
 * <p>
 * Added {@link ProtoListConverter} to read nested parquet written by SparkSQL
 */
class ProtoMessageConverter extends GroupConverter {

    private final Converter[] converters;
    private final ParentValueContainer parent;
    private final Message.Builder myBuilder;

    // used in record converter
    ProtoMessageConverter(ParentValueContainer pvc, Class<? extends Message> protoClass, GroupType parquetSchema) {
        this(pvc, Protobufs.getMessageBuilder(protoClass), parquetSchema);
    }


    // For usage in message arrays
    ProtoMessageConverter(ParentValueContainer pvc, Message.Builder builder, GroupType parquetSchema) {

        int schemaSize = parquetSchema.getFieldCount();
        converters = new Converter[schemaSize];

        this.parent = pvc;
        int parquetFieldIndex = 1;

        if (pvc == null) {
            throw new IllegalStateException("Missing parent value container");
        }

        myBuilder = builder;

        Descriptors.Descriptor protoDescriptor = builder.getDescriptorForType();

        for (Type parquetField : parquetSchema.getFields()) {
            Descriptors.FieldDescriptor protoField = protoDescriptor.findFieldByName(parquetField.getName());

            if (protoField == null) {
                String description = "Scheme mismatch \n\"" + parquetField + "\"" +
                        "\n proto descriptor:\n" + protoDescriptor.toProto();
                throw new IncompatibleSchemaModificationException("Cant find \"" + parquetField.getName() + "\" " + description);
            }

            converters[parquetFieldIndex - 1] = newMessageConverter(myBuilder, protoField, parquetField);

            parquetFieldIndex++;
        }
    }


    @Override
    public Converter getConverter(int fieldIndex) {
        return converters[fieldIndex];
    }

    @Override
    public void start() {

    }

    @Override
    public void end() {
        parent.add(myBuilder.build());
        myBuilder.clear();
    }

    private Converter newMessageConverter(final Message.Builder parentBuilder, final Descriptors.FieldDescriptor fieldDescriptor, Type parquetType) {

        boolean isRepeated = fieldDescriptor.isRepeated();

        ParentValueContainer parent;

        if (isRepeated) {
            parent = new ParentValueContainer() {
                @Override
                public void add(Object value) {
                    parentBuilder.addRepeatedField(fieldDescriptor, value);
                }
            };
        } else {
            parent = new ParentValueContainer() {
                @Override
                public void add(Object value) {
                    parentBuilder.setField(fieldDescriptor, value);
                }
            };
        }

        return newScalarConverter(parent, parentBuilder, fieldDescriptor, parquetType);
    }


    private Converter newScalarConverter(ParentValueContainer pvc, Message.Builder parentBuilder, Descriptors.FieldDescriptor fieldDescriptor, Type parquetType) {
        if (fieldDescriptor.isRepeated()
                && !parquetType.isRepetition(Type.Repetition.REPEATED)
                && parquetType.getName().equals(fieldDescriptor.getName())) {
            return new ProtoListConverter(pvc, parentBuilder, fieldDescriptor, parquetType.asGroupType());
        }

        JavaType javaType = fieldDescriptor.getJavaType();

        switch (javaType) {
            case STRING:
                return new ProtoStringConverter(pvc);
            case FLOAT:
                return new ProtoFloatConverter(pvc);
            case DOUBLE:
                return new ProtoDoubleConverter(pvc);
            case BOOLEAN:
                return new ProtoBooleanConverter(pvc);
            case BYTE_STRING:
                return new ProtoBinaryConverter(pvc);
            case ENUM:
                return new ProtoEnumConverter(pvc, fieldDescriptor);
            case INT:
                return new ProtoIntConverter(pvc);
            case LONG:
                return new ProtoLongConverter(pvc);
            case MESSAGE: {
                Message.Builder subBuilder = parentBuilder.newBuilderForField(fieldDescriptor);
                return new ProtoMessageConverter(pvc, subBuilder, parquetType.asGroupType());
            }
        }

        throw new UnsupportedOperationException(String.format("Cannot convert type: %s" +
                " (Parquet type: %s) ", javaType, parquetType));
    }

    public Message.Builder getBuilder() {
        return myBuilder;
    }

    static abstract class ParentValueContainer {

        /**
         * Adds the value to the parent.
         */
        public abstract void add(Object value);

    }

    final class ProtoEnumConverter extends PrimitiveConverter {

        private final Descriptors.FieldDescriptor fieldType;
        private final Map<Binary, Descriptors.EnumValueDescriptor> enumLookup;
        private final ParentValueContainer parent;
        private Descriptors.EnumValueDescriptor[] dict;

        public ProtoEnumConverter(ParentValueContainer parent, Descriptors.FieldDescriptor fieldType) {
            this.parent = parent;
            this.fieldType = fieldType;
            this.enumLookup = makeLookupStructure(fieldType);
        }

        /**
         * Fills lookup structure for translating between parquet enum values and Protocol buffer enum values.
         */
        private Map<Binary, Descriptors.EnumValueDescriptor> makeLookupStructure(Descriptors.FieldDescriptor enumFieldType) {
            Descriptors.EnumDescriptor enumType = enumFieldType.getEnumType();
            Map<Binary, Descriptors.EnumValueDescriptor> lookupStructure = new HashMap<Binary, Descriptors.EnumValueDescriptor>();

            List<Descriptors.EnumValueDescriptor> enumValues = enumType.getValues();

            for (Descriptors.EnumValueDescriptor value : enumValues) {
                String name = value.getName();
                lookupStructure.put(Binary.fromString(name), enumType.findValueByName(name));
            }

            return lookupStructure;
        }

        /**
         * Translates given parquet enum value to protocol buffer enum value.
         *
         * @throws org.apache.parquet.io.InvalidRecordException is there is no corresponding value.
         */
        private Descriptors.EnumValueDescriptor translateEnumValue(Binary binaryValue) {
            Descriptors.EnumValueDescriptor protoValue = enumLookup.get(binaryValue);

            if (protoValue == null) {
                Set<Binary> knownValues = enumLookup.keySet();
                String msg = "Illegal enum value \"" + binaryValue + "\""
                        + " in protocol buffer \"" + fieldType.getFullName() + "\""
                        + " legal values are: \"" + knownValues + "\"";
                throw new InvalidRecordException(msg);
            }
            return protoValue;
        }

        @Override
        final public void addBinary(Binary binaryValue) {
            Descriptors.EnumValueDescriptor protoValue = translateEnumValue(binaryValue);
            parent.add(protoValue);
        }

        @Override
        public void addValueFromDictionary(int dictionaryId) {
            parent.add(dict[dictionaryId]);
        }

        @Override
        public boolean hasDictionarySupport() {
            return true;
        }

        @Override
        public void setDictionary(Dictionary dictionary) {
            dict = new Descriptors.EnumValueDescriptor[dictionary.getMaxId() + 1];
            for (int i = 0; i <= dictionary.getMaxId(); i++) {
                Binary binaryValue = dictionary.decodeToBinary(i);
                dict[i] = translateEnumValue(binaryValue);
            }
        }

    }

    final class ProtoBinaryConverter extends PrimitiveConverter {

        final ParentValueContainer parent;

        public ProtoBinaryConverter(ParentValueContainer parent) {
            this.parent = parent;
        }

        @Override
        public void addBinary(Binary binary) {
            ByteString byteString = ByteString.copyFrom(binary.toByteBuffer());
            parent.add(byteString);
        }
    }


    final class ProtoBooleanConverter extends PrimitiveConverter {

        final ParentValueContainer parent;

        public ProtoBooleanConverter(ParentValueContainer parent) {
            this.parent = parent;
        }

        @Override
        final public void addBoolean(boolean value) {
            parent.add(value);
        }

    }

    final class ProtoDoubleConverter extends PrimitiveConverter {

        final ParentValueContainer parent;

        public ProtoDoubleConverter(ParentValueContainer parent) {
            this.parent = parent;
        }

        @Override
        public void addDouble(double value) {
            parent.add(value);
        }
    }

    final class ProtoFloatConverter extends PrimitiveConverter {

        final ParentValueContainer parent;

        public ProtoFloatConverter(ParentValueContainer parent) {
            this.parent = parent;
        }

        @Override
        public void addFloat(float value) {
            parent.add(value);
        }
    }

    final class ProtoIntConverter extends PrimitiveConverter {

        final ParentValueContainer parent;

        public ProtoIntConverter(ParentValueContainer parent) {
            this.parent = parent;
        }

        @Override
        public void addInt(int value) {
            parent.add(value);
        }
    }

    final class ProtoLongConverter extends PrimitiveConverter {

        final ParentValueContainer parent;

        public ProtoLongConverter(ParentValueContainer parent) {
            this.parent = parent;
        }

        @Override
        public void addLong(long value) {
            parent.add(value);
        }
    }

    final class ProtoStringConverter extends PrimitiveConverter {

        final ParentValueContainer parent;

        public ProtoStringConverter(ParentValueContainer parent) {
            this.parent = parent;
        }

        @Override
        public void addBinary(Binary binary) {
            String str = binary.toStringUsingUTF8();
            parent.add(str);
        }

    }

    final class ProtoListConverter extends GroupConverter {
        private final Converter converter;

        ProtoListConverter(ParentValueContainer parent, Message.Builder builder, Descriptors.FieldDescriptor fieldDescriptor, GroupType parquetSchema) {
            if (parent == null) {
                throw new IllegalStateException("Missing parent value container");
            }

            Type childSchema = parquetSchema.getFields().get(0);
            if (parquetSchema.isRepetition(Type.Repetition.REPEATED)) {
                converter = newScalarConverter(parent, builder, fieldDescriptor, childSchema);
            } else {
                converter = new ProtoListConverter(parent, builder, fieldDescriptor, childSchema.asGroupType());
            }
        }

        @Override
        public Converter getConverter(int fieldIndex) {
            if (fieldIndex != 0) {
                throw new IllegalArgumentException("lists have only one field. can't reach " + fieldIndex);
            }
            return converter;
        }

        @Override
        public void start() {
        }

        @Override
        public void end() {
        }
    }
}