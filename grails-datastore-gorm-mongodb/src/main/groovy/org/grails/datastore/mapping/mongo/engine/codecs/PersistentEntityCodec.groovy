/*
 * Copyright 2015 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.grails.datastore.mapping.mongo.engine.codecs

import groovy.transform.CompileStatic
import org.bson.BsonArray
import org.bson.BsonBinary
import org.bson.BsonDocument
import org.bson.BsonDocumentWriter
import org.bson.BsonReader
import org.bson.BsonString
import org.bson.BsonType
import org.bson.BsonValue
import org.bson.BsonWriter
import org.bson.Document
import org.bson.codecs.Codec
import org.bson.codecs.DecoderContext
import org.bson.codecs.EncoderContext
import org.bson.codecs.configuration.CodecRegistry
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import org.grails.datastore.mapping.config.Property
import org.grails.datastore.mapping.core.DatastoreException
import org.grails.datastore.mapping.dirty.checking.DirtyCheckable
import org.grails.datastore.mapping.dirty.checking.DirtyCheckableCollection
import org.grails.datastore.mapping.dirty.checking.DirtyCheckingCollection
import org.grails.datastore.mapping.dirty.checking.DirtyCheckingMap
import org.grails.datastore.mapping.dirty.checking.DirtyCheckingSupport
import org.grails.datastore.mapping.engine.EntityAccess
import org.grails.datastore.mapping.engine.internal.MappingUtils
import org.grails.datastore.mapping.engine.types.CustomTypeMarshaller
import org.grails.datastore.mapping.model.MappingContext
import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.datastore.mapping.model.PersistentProperty
import org.grails.datastore.mapping.model.PropertyMapping
import org.grails.datastore.mapping.model.types.Association
import org.grails.datastore.mapping.model.types.Basic
import org.grails.datastore.mapping.model.types.Custom
import org.grails.datastore.mapping.model.types.Embedded
import org.grails.datastore.mapping.model.types.EmbeddedCollection
import org.grails.datastore.mapping.model.types.Identity
import org.grails.datastore.mapping.model.types.ManyToOne
import org.grails.datastore.mapping.model.types.OneToMany
import org.grails.datastore.mapping.model.types.OneToOne
import org.grails.datastore.mapping.model.types.Simple
import org.grails.datastore.mapping.model.types.ToOne
import org.grails.datastore.mapping.mongo.AbstractMongoSession
import org.grails.datastore.mapping.mongo.MongoDatastore
import org.grails.datastore.mapping.mongo.engine.FastClassData
import org.grails.datastore.mapping.mongo.engine.MongoCodecEntityPersister
import org.grails.datastore.mapping.proxy.ProxyFactory
import org.springframework.core.convert.ConversionService

import javax.persistence.CascadeType
import javax.persistence.FetchType


/**
 * A MongoDB codec for persisting {@link PersistentEntity} instances
 *
 * @author Graeme Rocher
 * @since 4.1
 */
@CompileStatic
class PersistentEntityCodec implements Codec {
    private static final Map<Class, PropertyEncoder> ENCODERS = [:]
    private static final Map<Class, PropertyDecoder> DECODERS = [:]
    private static final String BLANK_STRING = ""
    public static final String MONGO_SET_OPERATOR = '$set'
    public static final String MONGO_UNSET_OPERATOR = '$unset'
    public static final EncoderContext DEFAULT_ENCODER_CONTEXT = EncoderContext.builder().build()

    static {
        ENCODERS[Identity] = new IdentityEncoder()
        DECODERS[Identity] = new IdentityDecoder()
        ENCODERS[Simple] = new SimpleEncoder()
        DECODERS[Simple] = new SimpleDecoder()
        ENCODERS[Embedded] = new EmbeddedEncoder()
        DECODERS[Embedded] = new EmbeddedDecoder()
        ENCODERS[EmbeddedCollection] = new EmbeddedCollectionEncoder()
        DECODERS[EmbeddedCollection] = new EmbeddedCollectionDecoder()
        ENCODERS[OneToOne] = new ToOneEncoder()
        DECODERS[OneToOne] = new ToOneDecoder()
        ENCODERS[ManyToOne] = new ToOneEncoder()
        DECODERS[ManyToOne] = new ToOneDecoder()
        ENCODERS[Custom] = new CustomTypeEncoder()
        DECODERS[Custom] = new CustomTypeDecoder()
        ENCODERS[Basic] = new BasicCollectionTypeEncoder()
        DECODERS[Basic] = new BasicCollectionTypeDecoder()
    }

    final MongoDatastore datastore
    final MappingContext mappingContext
    final PersistentEntity entity

    PersistentEntityCodec(MongoDatastore datastore, PersistentEntity entity) {
        this.datastore = datastore
        this.mappingContext = datastore.mappingContext
        this.entity = entity
    }

    @Override
    Object decode(BsonReader bsonReader, DecoderContext decoderContext) {
        bsonReader.readStartDocument()
        def persistentEntity = entity
        def instance = persistentEntity.javaClass.newInstance()
        EntityAccess access = createEntityAccess(instance)
        BsonType bsonType = bsonReader.readBsonType()
        while(bsonType != BsonType.END_OF_DOCUMENT) {

            def name = bsonReader.readName()
            if(MongoCodecEntityPersister.MONGO_CLASS_FIELD == name) {
                def childEntity = mappingContext
                                        .getChildEntityByDiscriminator(persistentEntity.rootEntity, bsonReader.readString())
                if(childEntity != null) {
                    persistentEntity = childEntity
                    instance = childEntity
                                .newInstance()
                    access = createEntityAccess(childEntity, instance)
                }
                bsonType = bsonReader.readBsonType()
                continue
            }

            if(MongoCodecEntityPersister.MONGO_ID_FIELD == name) {
                DECODERS[Identity].decode( bsonReader, persistentEntity.identity, access, decoderContext, datastore)
            }
            else {
                def property = persistentEntity.getPropertyByName(name)
                if(property && bsonType != BsonType.NULL) {
                    def propKind = property.getClass().superclass
                    switch(property.type) {
                        case CharSequence:
                            access.setPropertyNoConversion(name, bsonReader.readString())
                        break
                        default:
                            DECODERS[propKind]?.decode(bsonReader, property, access, decoderContext, datastore)
                    }

                }
                else {
                    bsonReader.skipValue()
                }

            }
            bsonType = bsonReader.readBsonType()
        }
        bsonReader.readEndDocument()
        return instance

    }

    protected EntityAccess createEntityAccess(Object instance) {
        def entity = mappingContext.getPersistentEntity(instance.getClass().name)
        return createEntityAccess(entity, instance)
    }

    protected EntityAccess createEntityAccess(PersistentEntity entity, instance) {
        AbstractMongoSession session = (AbstractMongoSession) getDatastore().currentSession
        return session.createEntityAccess(entity, instance)
    }

    @Override
    void encode(BsonWriter writer, Object value, EncoderContext encoderContext) {
        boolean includeIdentifier = true

        encode(writer, value, encoderContext, includeIdentifier)
    }

    /**
     * This method will encode an update for the given object based
     * @param value A {@link Bson} that is the update object
     * @return A Bson
     */
    Document encodeUpdate(Object value, EntityAccess access = createEntityAccess(value), EncoderContext encoderContext = DEFAULT_ENCODER_CONTEXT) {
        Document update = new Document()
        def entity = access.persistentEntity

        def proxyFactory = mappingContext.proxyFactory
        if( proxyFactory.isProxy(value) ) {
            value = proxyFactory.unwrap(value)
        }
        if(value instanceof DirtyCheckable) {
            def sets = new BsonDocument()
            def unsets = new Document()
            def writer = new BsonDocumentWriter(sets)
            writer.writeStartDocument()
            DirtyCheckable dirty = (DirtyCheckable)value
            Set<String> processed = []

            def dirtyProperties = dirty.listDirtyPropertyNames()
            boolean isNew = dirtyProperties.isEmpty() && dirty.hasChanged()
            if(isNew) {
                // if it is new it can only be an embedded entity that has now been updated
                // so we get all properties
                dirtyProperties = entity.persistentPropertyNames
                if(!entity.isRoot()) {
                    sets[MongoCodecEntityPersister.MONGO_CLASS_FIELD] = new BsonString(entity.discriminator)
                }

            }

            for(propertyName in dirtyProperties) {
                def prop = entity.getPropertyByName(propertyName)
                if(prop != null) {

                    processed << propertyName
                    Object v = access.getProperty(prop.name)
                    if (v != null) {
                        if(prop instanceof Embedded) {
                            encodeEmbeddedUpdate(sets, (Association)prop, v)
                        }
                        else if(prop instanceof EmbeddedCollection) {
                            encodeEmbeddedCollectionUpdate(access, sets, (Association)prop, v)
                        }
                        else {
                            def propKind = prop.getClass().superclass
                            ENCODERS[propKind]?.encode(writer, prop, v, access, encoderContext, datastore)
                        }

                    }
                    else if(!isNew) {
                        unsets[prop.name] = BLANK_STRING
                    }
                }
            }

            for(association in entity.associations) {
                if(processed.contains( association.name )) continue
                if(association instanceof OneToMany) {
                    def v = access.getProperty(association.name)
                    if (v != null) {
                        // TODO: handle unprocessed association
                    }
                }
                else if(association instanceof ToOne) {
                    def v = access.getProperty(association.name)
                    if( v instanceof DirtyCheckable ) {
                        if(((DirtyCheckable)v).hasChanged()) {
                            encodeEmbeddedUpdate(sets, association, v)
                        }
                    }
                }
                else if(association instanceof EmbeddedCollection) {
                    def v = access.getProperty(association.name)
                    if( v instanceof DirtyCheckableCollection ) {
                        if(((DirtyCheckableCollection)v).hasChanged()) {
                            encodeEmbeddedCollectionUpdate(access, sets, association, v)
                        }
                    }
                }
            }

            writer.writeEndDocument()

            if(sets) {
                update[MONGO_SET_OPERATOR] = sets
            }
            if(unsets) {
                update[MONGO_UNSET_OPERATOR] = unsets
            }
        }
        else {
            // TODO: Support non-dirty checkable objects?
        }

        return update
    }

    protected void encodeEmbeddedCollectionUpdate(EntityAccess parentAccess, BsonDocument sets, Association association, v) {
        if(v instanceof Collection) {
            if((v instanceof DirtyCheckableCollection) && !((DirtyCheckableCollection)v).hasChangedSize()) {
                int i = 0
                for(o in v) {
                    def embeddedUpdate = encodeUpdate(o)
                    def embeddedSets = embeddedUpdate.get(MONGO_SET_OPERATOR)
                    if(embeddedSets) {

                        def map = (Map) embeddedSets
                        for (key in map.keySet()) {
                            sets.put("${association.name}.${i}.$key", (BsonValue) map.get(key))
                        }
                    }
                    i++
                }
            }
            else {
                // if this is not a dirty checkable collection or the collection has changed size then a whole new collection has been
                // set so we overwrite existing
                def associatedEntity = association.associatedEntity
                def rootClass = associatedEntity.javaClass
                def mongoDatastore = this.datastore
                def entityCodec = mongoDatastore.getPersistentEntityCodec(rootClass)
                def inverseProperty = association.inverseSide
                List<BsonValue> documents =[]
                for(o in v) {
                    if(o == null) {
                        documents << null
                        continue
                    }
                    PersistentEntity entity = associatedEntity
                    PersistentEntityCodec codec = entityCodec

                    def cls = o.getClass()
                    if(rootClass != cls) {
                        // a subclass, so lookup correct codec
                        entity = mongoDatastore.mappingContext.getPersistentEntity(cls.name)
                        if(entity == null) {
                            throw new DatastoreException("Value [$o] is not a valid type for association [$association]" )
                        }
                        codec = mongoDatastore.getPersistentEntityCodec(cls)
                    }
                    def ea = createEntityAccess(entity, o)
                    if(inverseProperty != null) {
                        if(inverseProperty instanceof ToOne) {
                            ea.setPropertyNoConversion( inverseProperty.name, parentAccess.entity)
                        }

                    }
                    def doc = new BsonDocument()
                    def id = ea.identifier
                    codec.encode( new BsonDocumentWriter(doc), o, DEFAULT_ENCODER_CONTEXT, id != null )
                    documents << doc
                }
                def bsonArray = new BsonArray(documents)
                sets.put( association.name, bsonArray)
            }
        }
        else {
            // TODO: Map handling
        }

    }
    protected void encodeEmbeddedUpdate(BsonDocument sets, Association association, v) {
        def embeddedUpdate = encodeUpdate(v)
        def embeddedSets = embeddedUpdate.get(MONGO_SET_OPERATOR)
        if(embeddedSets) {

            def map = (Map) embeddedSets
            for (key in map.keySet()) {
                sets.put("${association.name}.$key", (BsonValue) map.get(key))
            }
        }
    }

    void encode(BsonWriter writer, value, EncoderContext encoderContext, boolean includeIdentifier) {
        writer.writeStartDocument()
        def access = createEntityAccess(value)
        def entity = access.persistentEntity

        if(!entity.isRoot()) {
            def discriminator = entity.discriminator
            writer.writeName(MongoCodecEntityPersister.MONGO_CLASS_FIELD)
            writer.writeString(discriminator)
        }

        def mongoDatastore = datastore
        if (includeIdentifier) {

            def id = access.getIdentifier()
            ENCODERS[Identity].encode writer, entity.identity, id, access, encoderContext, mongoDatastore
        }



        for (PersistentProperty prop in entity.persistentProperties) {
            def propKind = prop.getClass().superclass
            Object v = access.getProperty(prop.name)
            if (v != null) {
                ENCODERS[propKind]?.encode(writer, (PersistentProperty) prop, v, access, encoderContext, mongoDatastore)
            }
        }

        writer.writeEndDocument()
        writer.flush()
    }

    @Override
    Class getEncoderClass() {
        entity.javaClass
    }

    /**
     * An interface for encoding PersistentProperty instances
     */
    static interface PropertyEncoder<T extends PersistentProperty> {
        void encode(BsonWriter writer, T property, Object value, EntityAccess parentAccess, EncoderContext encoderContext, MongoDatastore datastore)
    }

    /**
     * An interface for encoding PersistentProperty instances
     */
    static interface PropertyDecoder<T extends PersistentProperty> {
        void decode(BsonReader reader, T property, EntityAccess entityAccess, DecoderContext decoderContext, MongoDatastore datastore)
    }

    /**
     * A {@PropertyDecoder} capable of decoding the {@link Identity}
     */
    static class IdentityDecoder implements PropertyDecoder<Identity> {

        @Override
        void decode(BsonReader bsonReader, Identity property, EntityAccess access, DecoderContext decoderContext, MongoDatastore datastore) {
            switch(property.type) {
                case ObjectId:
                    access.setIdentifier( bsonReader.readObjectId() )
                    break
                case Long:
                    access.setIdentifier( bsonReader.readInt64() )
                    break
                case Integer:
                    access.setIdentifier( bsonReader.readInt32() )
                    break
                default:
                    access.setIdentifier( bsonReader.readString())
            }

        }
    }
    /**
     * A {@PropertyEncoder} capable of encoding the {@link Identity}
     */
    static class IdentityEncoder implements PropertyEncoder<Identity> {

        @Override
        void encode(BsonWriter writer, Identity property, Object id, EntityAccess parentAccess, EncoderContext encoderContext, MongoDatastore datastore) {
            writer.writeName(MongoCodecEntityPersister.MONGO_ID_FIELD)

            if (id instanceof ObjectId) {
                writer.writeObjectId(id)
            } else if (id instanceof Number) {
                writer.writeInt64(((Number) id).toLong())
            } else {
                writer.writeString(id.toString())
            }

        }
    }
    /**
     * A {@PropertyDecoder} capable of decoding {@link Simple} properties
     */
    static class SimpleDecoder implements PropertyDecoder<Simple> {
        public static final Map<Class, TypeDecoder> SIMPLE_TYPE_DECODERS
        public static final TypeDecoder DEFAULT_DECODER = new TypeDecoder() {
            @Override
            void decode(BsonReader reader, Simple property, EntityAccess entityAccess) {
                entityAccess.setProperty( property.name, reader.readString())
            }
        }
        static interface TypeDecoder {
            void decode(BsonReader reader, Simple property, EntityAccess entityAccess)
        }

        static {
            SIMPLE_TYPE_DECODERS = new HashMap<Class, TypeDecoder>().withDefault { Class ->
                DEFAULT_DECODER
            }

             def convertingIntReader = new TypeDecoder() {
                @Override
                void decode(BsonReader reader, Simple property, EntityAccess entityAccess) {
                    entityAccess.setProperty( property.name, reader.readInt32() )
                }
            }
            SIMPLE_TYPE_DECODERS[Short] = convertingIntReader
            SIMPLE_TYPE_DECODERS[Byte] = convertingIntReader
            SIMPLE_TYPE_DECODERS[Integer] = new TypeDecoder() {
                @Override
                void decode(BsonReader reader, Simple property, EntityAccess entityAccess) {
                    entityAccess.setPropertyNoConversion( property.name, reader.readInt32() )
                }
            }
            SIMPLE_TYPE_DECODERS[Long] = new TypeDecoder() {
                @Override
                void decode(BsonReader reader, Simple property, EntityAccess entityAccess) {
                    entityAccess.setPropertyNoConversion( property.name, reader.readInt64() )
                }
            }
            SIMPLE_TYPE_DECODERS[Double] = new TypeDecoder() {
                @Override
                void decode(BsonReader reader, Simple property, EntityAccess entityAccess) {
                    entityAccess.setPropertyNoConversion( property.name, reader.readDouble() )
                }
            }
            SIMPLE_TYPE_DECODERS[Boolean] = new TypeDecoder() {
                @Override
                void decode(BsonReader reader, Simple property, EntityAccess entityAccess) {
                    entityAccess.setPropertyNoConversion( property.name, reader.readBoolean() )
                }
            }

            SIMPLE_TYPE_DECODERS[([] as byte[]).getClass()] = new TypeDecoder() {
                @Override
                void decode(BsonReader reader, Simple property, EntityAccess entityAccess) {
                    def binary = reader.readBinaryData()
                    entityAccess.setPropertyNoConversion( property.name, binary.data )
                }
            }
            SIMPLE_TYPE_DECODERS[Date] = new TypeDecoder() {
                @Override
                void decode(BsonReader reader, Simple property, EntityAccess entityAccess) {
                    def time = reader.readDateTime()
                    entityAccess.setPropertyNoConversion( property.name, new Date(time))
                }
            }
            SIMPLE_TYPE_DECODERS[Calendar] = new TypeDecoder() {
                @Override
                void decode(BsonReader reader, Simple property, EntityAccess entityAccess) {
                    def time = reader.readDateTime()
                    def calendar = new GregorianCalendar()
                    calendar.setTimeInMillis(time)
                    entityAccess.setPropertyNoConversion( property.name, calendar)
                }
            }
        }

        @Override
        void decode(BsonReader reader, Simple property, EntityAccess entityAccess, DecoderContext decoderContext, MongoDatastore datastore) {
            def type = property.type
            def decoder = SIMPLE_TYPE_DECODERS[type]
            if(type.isArray()) {
                if(!decoder.is(DEFAULT_DECODER)) {
                    decoder.decode reader, property, entityAccess
                }
                else {
                    def arrayDecoder = datastore.codecRegistry.get(List)
                    def bsonArray = arrayDecoder.decode(reader, decoderContext)
                    entityAccess.setProperty(property.name, bsonArray)
                }
            }
            else {
                decoder.decode reader, property, entityAccess
            }
        }
    }
    /**
     * An encoder for simple types persistable by MongoDB
     *
     * @author Graeme Rocher
     * @since 4.1
     */
    static class SimpleEncoder implements PropertyEncoder<Simple> {

        static interface TypeEncoder {
            void encode(BsonWriter writer, Simple property, Object value)
        }

        public static final Map<Class, TypeEncoder> SIMPLE_TYPE_ENCODERS
        public static final TypeEncoder DEFAULT_ENCODER = new TypeEncoder() {
            @Override
            void encode(BsonWriter writer, Simple property, Object value) {
                writer.writeString( value.toString() )
            }
        }

        static {


            SIMPLE_TYPE_ENCODERS = new HashMap<Class, TypeEncoder>().withDefault { Class ->
                DEFAULT_ENCODER
            }

            TypeEncoder smallNumberEncoder = new TypeEncoder() {
                @Override
                void encode(BsonWriter writer, Simple property, Object value) {
                    writer.writeInt32( ((Number)value).intValue() )
                }
            }
            SIMPLE_TYPE_ENCODERS[CharSequence] = DEFAULT_ENCODER
            SIMPLE_TYPE_ENCODERS[String] = DEFAULT_ENCODER
            SIMPLE_TYPE_ENCODERS[StringBuffer] = DEFAULT_ENCODER
            SIMPLE_TYPE_ENCODERS[StringBuilder] = DEFAULT_ENCODER
            SIMPLE_TYPE_ENCODERS[BigInteger] = DEFAULT_ENCODER
            SIMPLE_TYPE_ENCODERS[BigDecimal] = DEFAULT_ENCODER
            SIMPLE_TYPE_ENCODERS[Byte] = smallNumberEncoder
            SIMPLE_TYPE_ENCODERS[Integer] = smallNumberEncoder
            SIMPLE_TYPE_ENCODERS[Short] = smallNumberEncoder
            SIMPLE_TYPE_ENCODERS[Double] = new TypeEncoder() {
                @Override
                void encode(BsonWriter writer, Simple property, Object value) {
                    writer.writeDouble( (Double)value )
                }
            }
            SIMPLE_TYPE_ENCODERS[Long] = new TypeEncoder() {
                @Override
                void encode(BsonWriter writer, Simple property, Object value) {
                    writer.writeInt64( (Long)value )
                }
            }
            SIMPLE_TYPE_ENCODERS[Boolean] = new TypeEncoder() {
                @Override
                void encode(BsonWriter writer, Simple property, Object value) {
                    writer.writeBoolean( (Boolean)value )
                }
            }
            SIMPLE_TYPE_ENCODERS[Calendar] = new TypeEncoder() {
                @Override
                void encode(BsonWriter writer, Simple property, Object value) {
                    writer.writeDateTime( ((Calendar)value).timeInMillis )
                }
            }
            SIMPLE_TYPE_ENCODERS[Date] = new TypeEncoder() {
                @Override
                void encode(BsonWriter writer, Simple property, Object value) {
                    writer.writeDateTime( ((Date)value).time )
                }
            }
            SIMPLE_TYPE_ENCODERS[TimeZone] = new TypeEncoder() {
                @Override
                void encode(BsonWriter writer, Simple property, Object value) {
                    writer.writeString( ((TimeZone)value).ID )
                }
            }
            SIMPLE_TYPE_ENCODERS[([] as byte[]).getClass()] = new TypeEncoder() {
                @Override
                void encode(BsonWriter writer, Simple property, Object value) {
                    writer.writeBinaryData( new BsonBinary((byte[])value))
                }
            }
        }

        @Override
        void encode(BsonWriter writer, Simple property, Object value, EntityAccess parentAccess, EncoderContext encoderContext, MongoDatastore datastore) {
            def type = property.type
            def encoder = SIMPLE_TYPE_ENCODERS[type]
            writer.writeName( MappingUtils.getTargetKey(property) )
            if(type.isArray()) {
                if(!encoder.is(DEFAULT_ENCODER)) {
                    encoder.encode(writer, property, value)
                }
                else {
                    writer.writeStartArray()
                    for( o in value ) {
                        encoder = SIMPLE_TYPE_ENCODERS[type.componentType]
                        encoder.encode(writer, property, o)
                    }
                    writer.writeEndArray()
                }
            }
            else {
                encoder.encode(writer, property, value)
            }
        }
    }

    /**
     * A {@PropertyDecoder} capable of decoding {@Custom} types
     */
    static class CustomTypeDecoder implements PropertyDecoder<Custom> {

        @Override
        void decode(BsonReader reader, Custom property, EntityAccess entityAccess, DecoderContext decoderContext, MongoDatastore datastore) {
            CustomTypeMarshaller marshaller = property.customTypeMarshaller

            decode(datastore, reader, decoderContext, marshaller, property, entityAccess)
        }

        protected static void decode(MongoDatastore datastore, BsonReader reader, DecoderContext decoderContext, CustomTypeMarshaller marshaller, PersistentProperty property, EntityAccess entityAccess) {
            def registry = datastore.getCodecRegistry()
            def documentCodec = registry.get(Document)
            def bsonType = reader.currentBsonType
            if(bsonType == BsonType.DOCUMENT) {

                Document doc = documentCodec.decode(reader, decoderContext)

                def value = marshaller.read(property, new Document(
                        MappingUtils.getTargetKey(property),
                        doc
                ))
                if (value != null) {
                    entityAccess.setPropertyNoConversion(property.name, value)
                }
            }
            else if(bsonType == BsonType.ARRAY) {
                def arrayCodec = registry.get(List)
                def array = arrayCodec.decode(reader, decoderContext)
                def value = marshaller.read(property, new Document(
                        MappingUtils.getTargetKey(property),
                        array
                ))
                if (value != null) {
                    entityAccess.setPropertyNoConversion(property.name, value)
                }
            }
            else {
                reader.skipValue()
            }
        }
    }

    /**
     * A {@PropertyEncoder} capable of encoding {@Custom} types
     */
    static class CustomTypeEncoder implements PropertyEncoder<Custom> {

        @Override
        void encode(BsonWriter writer, Custom property, Object value, EntityAccess parentAccess, EncoderContext encoderContext, MongoDatastore datastore) {
            def marshaller = property.customTypeMarshaller
            encode(datastore, encoderContext, writer, property, marshaller, value)

        }

        protected static void encode(MongoDatastore datastore, EncoderContext encoderContext, BsonWriter writer, PersistentProperty property, CustomTypeMarshaller marshaller, value) {
            String targetName = MappingUtils.getTargetKey(property)
            def document = new Document()
            marshaller.write(property, value, document)

            Object converted = document.get(targetName)
            if (converted instanceof Document) {
                Codec codec = (Codec) datastore.codecRegistry.get(converted.getClass())
                if (codec) {
                    writer.writeName(targetName)
                    codec.encode(writer, converted, encoderContext)
                }
            }
            else if(converted instanceof List) {
                Codec<List> codec = datastore.codecRegistry.get(List)
                if(codec) {
                    writer.writeName(targetName)
                    codec.encode(writer, converted, encoderContext)
                }
            }
        }
    }

    /**
     * A {@PropertyEncoder} capable of encoding {@ToOne} association types
     */
    static class ToOneEncoder implements PropertyEncoder<ToOne> {

        @Override
        void encode(BsonWriter writer, ToOne property, Object value, EntityAccess parentAccess, EncoderContext encoderContext, MongoDatastore datastore) {
            if(value) {
                def associatedEntity = property.associatedEntity
                def proxyFactory = datastore.mappingContext.proxyFactory
                def codecRegistry = datastore.codecRegistry

                Object associationId
                if(property.doesCascade(CascadeType.PERSIST) && associatedEntity != null) {
                    if(proxyFactory.isProxy(value)) {
                        associationId = proxyFactory.getIdentifier(value)
                    }
                    else {
                        AbstractMongoSession mongoSession = (AbstractMongoSession)datastore.currentSession
                        def associationAccess = mongoSession.createEntityAccess(associatedEntity, value)
                        associationId = associationAccess.identifier
                    }

                    if(associationId != null) {
                        writer.writeName MappingUtils.getTargetKey(property)
                        codecRegistry.get(associationId.getClass()).encode( writer, associationId, encoderContext)
                    }
                }
            }
        }
    }

    /**
     * A {@PropertyEncoder} capable of encoding {@ToOne} association types
     */
    static class ToOneDecoder implements PropertyDecoder<ToOne> {

        @Override
        void decode(BsonReader bsonReader, ToOne property, EntityAccess entityAccess, DecoderContext decoderContext, MongoDatastore datastore) {
            def mongoSession = datastore.currentSession
            boolean isLazy = isLazyAssociation(property.mapping)
            def associatedEntity = property.associatedEntity
            if(associatedEntity == null) {
                bsonReader.skipValue()
                return
            }

            Serializable associationId
            switch(associatedEntity.identity.type) {
                case ObjectId:
                    associationId = bsonReader.readObjectId()
                    break
                case Long:
                    associationId = (Long)bsonReader.readInt64()
                    break
                case Integer:
                    associationId =  (Integer)bsonReader.readInt32()
                    break
                default:
                    associationId = bsonReader.readString()
            }

            if(isLazy) {
                entityAccess.setPropertyNoConversion(
                        property.name,
                        mongoSession.proxy(associatedEntity.javaClass, associationId )
                )
            }
            else {
                entityAccess.setPropertyNoConversion(
                        property.name,
                        mongoSession.retrieve(associatedEntity.javaClass, associationId )
                )
            }

        }

        private boolean isLazyAssociation(PropertyMapping<Property> associationPropertyMapping) {
            if (associationPropertyMapping == null) {
                return true
            }

            Property kv = associationPropertyMapping.getMappedForm()
            return kv.getFetchStrategy() == FetchType.LAZY
        }

    }

    /**
     * A {@PropertyEncoder} capable of encoding {@Embedded} association types
     */
    static class EmbeddedEncoder implements PropertyEncoder<Embedded> {

        @Override
        void encode(BsonWriter writer, Embedded property, Object value, EntityAccess parentAccess, EncoderContext encoderContext, MongoDatastore datastore) {
            if(value) {
                def associatedEntity = property.associatedEntity
                def registry = datastore.codecRegistry
                writer.writeName property.name
                def access = datastore.createEntityAccess(associatedEntity, value)

                PersistentEntityCodec codec = (PersistentEntityCodec)registry.get(associatedEntity.javaClass)
                codec.encode(writer, value, encoderContext, access.identifier ? true : false)
            }
        }
    }

    /**
     * A {@PropertyDecoder} capable of decoding {@Embedded} association types
     */
    static class EmbeddedDecoder implements PropertyDecoder<Embedded> {

        @Override
        void decode(BsonReader reader, Embedded property, EntityAccess entityAccess, DecoderContext decoderContext, MongoDatastore datastore) {
            def associatedEntity = property.associatedEntity
            def registry = datastore.codecRegistry


            PersistentEntityCodec codec = (PersistentEntityCodec)registry.get(associatedEntity.javaClass)

            def decoded = codec.decode(reader, decoderContext)
            if(decoded instanceof DirtyCheckable) {
                decoded.trackChanges()
            }
            entityAccess.setPropertyNoConversion(
                    property.name,
                    decoded
            )

        }
    }

    /**
     * A {@PropertyEncoder} capable of encoding {@EmbeddedCollection} collection types
     */
    static class EmbeddedCollectionEncoder implements PropertyEncoder<EmbeddedCollection> {

        @Override
        void encode(BsonWriter writer, EmbeddedCollection property, Object value, EntityAccess parentAccess, EncoderContext encoderContext, MongoDatastore datastore) {

            writer.writeName property.name
            writer.writeStartArray()

            def associatedEntity = property.associatedEntity
            PersistentEntityCodec associatedCodec = datastore.getPersistentEntityCodec( associatedEntity.javaClass )
            def isBidirectional = property.isBidirectional()
            Association inverseSide = isBidirectional ? property.inverseSide : null
            String inverseProperty = isBidirectional ? inverseSide.name : null
            def isToOne = inverseSide instanceof ToOne
            def mappingContext = datastore.mappingContext

            for(v in value) {
                if(v != null) {
                    PersistentEntityCodec codec = associatedCodec
                    PersistentEntity entity = associatedEntity

                    def cls = v.getClass()
                    if(cls != associatedEntity.javaClass) {
                        // try subclass

                        def childEntity = mappingContext.getPersistentEntity(cls.name)
                        if(childEntity != null) {
                            entity = childEntity
                            codec = datastore.getPersistentEntityCodec(cls)
                        }
                        else {
                            continue
                        }
                    }

                    def ea = ((AbstractMongoSession)datastore.currentSession)
                            .createEntityAccess(entity, v)
                    def id = ea.getIdentifier()
                    if(isBidirectional) {
                        if(isToOne) {
                            ea.setPropertyNoConversion(inverseProperty, parentAccess.entity)
                        }
                    }

                    codec.encode(writer, v, encoderContext, id != null)
                }
            }

            writer.writeEndArray()

        }
    }
    /**
     * A {@PropertyDecoder} capable of decoding {@EmbeddedCollection} collection types
     */
    static class EmbeddedCollectionDecoder implements PropertyDecoder<EmbeddedCollection> {

        @Override
        void decode(BsonReader reader, EmbeddedCollection property, EntityAccess entityAccess, DecoderContext decoderContext, MongoDatastore datastore) {
            def associatedEntity = property.associatedEntity
            def associationCodec = datastore.getPersistentEntityCodec(associatedEntity.javaClass)
            if(Collection.isAssignableFrom(property.type)) {
                reader.readStartArray()
                def bsonType = reader.readBsonType()
                def collection = MappingUtils.createConcreteCollection(property.type)
                while(bsonType != BsonType.END_OF_DOCUMENT) {
                    collection << associationCodec.decode(reader, decoderContext)
                    bsonType = reader.readBsonType()
                }
                reader.readEndArray()
                entityAccess.setPropertyNoConversion(
                        property.name,
                        DirtyCheckingSupport.wrap(collection, (DirtyCheckable)entityAccess.entity, property.name)
                )
            }
        }
    }
    /**
     * A {@PropertyDecoder} capable of decoding {@Basic} collection types
     */
    static class BasicCollectionTypeDecoder implements PropertyDecoder<Basic> {

        @Override
        void decode(BsonReader reader, Basic property, EntityAccess entityAccess, DecoderContext decoderContext, MongoDatastore datastore) {
            CustomTypeMarshaller marshaller = property.customTypeMarshaller

            if(marshaller) {
                CustomTypeDecoder.decode(datastore, reader, decoderContext, marshaller, property, entityAccess)
            }
            else {
                def conversionService = datastore.mappingContext.conversionService
                def componentType = property.componentType
                Codec codec = datastore.codecRegistry.get(property.type)
                def value = codec.decode(reader, decoderContext)
                def entity = entityAccess.entity
                if(value instanceof Collection) {
                    def converted = value.collect() { conversionService.convert(it, componentType) }


                    if(entity instanceof DirtyCheckable) {
                        converted = DirtyCheckingSupport.wrap(converted, (DirtyCheckable) entity, property.name)
                    }
                    entityAccess.setProperty( property.name, converted )
                }
                else if(value instanceof Map) {
                    def converted = value.collectEntries() { Map.Entry entry ->
                        def v = entry.value
                        entry.value = conversionService.convert(v, componentType)
                        return entry
                    }
                    if(entity instanceof DirtyCheckable) {
                        converted = new DirtyCheckingMap(converted, (DirtyCheckable) entity, property.name)
                    }
                    entityAccess.setProperty( property.name, converted)
                }
            }
        }
    }

    /**
     * A {@PropertyEncoder} capable of encoding {@Basic}  collection types
     */
    static class BasicCollectionTypeEncoder implements PropertyEncoder<Basic> {

        @Override
        void encode(BsonWriter writer, Basic property, Object value, EntityAccess parentAccess, EncoderContext encoderContext, MongoDatastore datastore) {
            def marshaller = property.customTypeMarshaller
            if(marshaller) {
                CustomTypeEncoder.encode(datastore, encoderContext, writer, property, marshaller, value)
            }
            else {
                writer.writeName( MappingUtils.getTargetKey(property) )
                Codec codec = datastore.codecRegistry.get(property.type)
                codec.encode(writer, value, encoderContext)
                def parent = parentAccess.entity
                if(parent instanceof DirtyCheckable) {
                    if(value instanceof Collection) {
                        def propertyName = property.name
                        parentAccess.setPropertyNoConversion(
                                propertyName,
                                DirtyCheckingSupport.wrap(value, parent, propertyName)
                        )
                    }
                    else if(value instanceof Map) {
                        def propertyName = property.name
                        parentAccess.setPropertyNoConversion(
                                propertyName,
                                new DirtyCheckingMap(value, parent, propertyName)
                        )
                    }
                }
            }
        }
    }
}
