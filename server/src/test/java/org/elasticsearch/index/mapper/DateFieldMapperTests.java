/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.mapper;

import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.IndexableField;
import org.elasticsearch.Version;
import org.elasticsearch.bootstrap.JavaVersion;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.time.DateFormatter;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.mapper.DateFieldMapper.Resolution;
import org.elasticsearch.index.mapper.MapperService.MergeReason;
import org.elasticsearch.index.termvectors.TermVectorsService;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.search.lookup.SourceLookup;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.elasticsearch.test.InternalSettingsPlugin;
import org.junit.Before;

import java.io.IOException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;

public class DateFieldMapperTests extends ESSingleNodeTestCase {

    IndexService indexService;
    DocumentMapperParser parser;

    @Before
    public void setup() {
        indexService = createIndex("test");
        parser = indexService.mapperService().documentMapperParser();
    }

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return pluginList(InternalSettingsPlugin.class);
    }

    public void testDefaults() throws Exception {
        String mapping = Strings.toString(XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("properties").startObject("field").field("type", "date").endObject().endObject()
                .endObject().endObject());

        DocumentMapper mapper = parser.parse("type", new CompressedXContent(mapping));

        assertEquals(mapping, mapper.mappingSource().toString());

        ParsedDocument doc = mapper.parse(new SourceToParse("test", "1", BytesReference
                .bytes(XContentFactory.jsonBuilder()
                        .startObject()
                        .field("field", "2016-03-11")
                        .endObject()),
                XContentType.JSON));

        IndexableField[] fields = doc.rootDoc().getFields("field");
        assertEquals(2, fields.length);
        IndexableField pointField = fields[0];
        assertEquals(1, pointField.fieldType().pointIndexDimensionCount());
        assertEquals(8, pointField.fieldType().pointNumBytes());
        assertFalse(pointField.fieldType().stored());
        assertEquals(1457654400000L, pointField.numericValue().longValue());
        IndexableField dvField = fields[1];
        assertEquals(DocValuesType.SORTED_NUMERIC, dvField.fieldType().docValuesType());
        assertEquals(1457654400000L, dvField.numericValue().longValue());
        assertFalse(dvField.fieldType().stored());
    }

    public void testNotIndexed() throws Exception {
        String mapping = Strings.toString(XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("properties").startObject("field").field("type", "date").field("index", false).endObject().endObject()
                .endObject().endObject());

        DocumentMapper mapper = parser.parse("type", new CompressedXContent(mapping));

        assertEquals(mapping, mapper.mappingSource().toString());

        ParsedDocument doc = mapper.parse(new SourceToParse("test", "1", BytesReference
                .bytes(XContentFactory.jsonBuilder()
                        .startObject()
                        .field("field", "2016-03-11")
                        .endObject()),
                XContentType.JSON));

        IndexableField[] fields = doc.rootDoc().getFields("field");
        assertEquals(1, fields.length);
        IndexableField dvField = fields[0];
        assertEquals(DocValuesType.SORTED_NUMERIC, dvField.fieldType().docValuesType());
    }

    public void testNoDocValues() throws Exception {
        String mapping = Strings.toString(XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("properties").startObject("field").field("type", "date").field("doc_values", false).endObject().endObject()
                .endObject().endObject());

        DocumentMapper mapper = parser.parse("type", new CompressedXContent(mapping));

        assertEquals(mapping, mapper.mappingSource().toString());

        ParsedDocument doc = mapper.parse(new SourceToParse("test", "1", BytesReference
                .bytes(XContentFactory.jsonBuilder()
                        .startObject()
                        .field("field", "2016-03-11")
                        .endObject()),
                XContentType.JSON));

        IndexableField[] fields = doc.rootDoc().getFields("field");
        assertEquals(1, fields.length);
        IndexableField pointField = fields[0];
        assertEquals(1, pointField.fieldType().pointIndexDimensionCount());
    }

    public void testStore() throws Exception {
        String mapping = Strings.toString(XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("properties").startObject("field").field("type", "date").field("store", true).endObject().endObject()
                .endObject().endObject());

        DocumentMapper mapper = parser.parse("type", new CompressedXContent(mapping));

        assertEquals(mapping, mapper.mappingSource().toString());

        ParsedDocument doc = mapper.parse(new SourceToParse("test", "1", BytesReference
                .bytes(XContentFactory.jsonBuilder()
                        .startObject()
                        .field("field", "2016-03-11")
                        .endObject()),
                XContentType.JSON));

        IndexableField[] fields = doc.rootDoc().getFields("field");
        assertEquals(3, fields.length);
        IndexableField pointField = fields[0];
        assertEquals(1, pointField.fieldType().pointIndexDimensionCount());
        IndexableField dvField = fields[1];
        assertEquals(DocValuesType.SORTED_NUMERIC, dvField.fieldType().docValuesType());
        IndexableField storedField = fields[2];
        assertTrue(storedField.fieldType().stored());
        assertEquals(1457654400000L, storedField.numericValue().longValue());
    }

    public void testIgnoreMalformed() throws IOException {
        testIgnoreMalfomedForValue("2016-03-99",
                "failed to parse date field [2016-03-99] with format [strict_date_optional_time||epoch_millis]");
        testIgnoreMalfomedForValue("-2147483648",
                "Invalid value for Year (valid values -999999999 - 999999999): -2147483648");
    }

    private void testIgnoreMalfomedForValue(String value, String expectedException) throws IOException {
        String mapping = Strings.toString(XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("properties").startObject("field").field("type", "date").endObject().endObject()
                .endObject().endObject());

        DocumentMapper mapper = parser.parse("type", new CompressedXContent(mapping));

        assertEquals(mapping, mapper.mappingSource().toString());

        ThrowingRunnable runnable = () -> mapper.parse(new SourceToParse("test", "1", BytesReference
                .bytes(XContentFactory.jsonBuilder()
                        .startObject()
                        .field("field", value)
                        .endObject()),
                XContentType.JSON));
        MapperParsingException e = expectThrows(MapperParsingException.class, runnable);
        assertThat(e.getCause().getMessage(), containsString(expectedException));

        mapping = Strings.toString(XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("properties").startObject("field").field("type", "date")
                .field("ignore_malformed", true).endObject().endObject()
                .endObject().endObject());

        DocumentMapper mapper2 = parser.parse("type", new CompressedXContent(mapping));

        ParsedDocument doc = mapper2.parse(new SourceToParse("test", "1", BytesReference
                .bytes(XContentFactory.jsonBuilder()
                        .startObject()
                        .field("field", value)
                        .endObject()),
                XContentType.JSON));

        IndexableField[] fields = doc.rootDoc().getFields("field");
        assertEquals(0, fields.length);
        assertArrayEquals(new String[] { "field" }, TermVectorsService.getValues(doc.rootDoc().getFields("_ignored")));
    }

    public void testChangeFormat() throws IOException {
        String mapping = Strings.toString(XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("properties").startObject("field").field("type", "date")
                .field("format", "epoch_second").endObject().endObject()
                .endObject().endObject());

        DocumentMapper mapper = parser.parse("type", new CompressedXContent(mapping));

        assertEquals(mapping, mapper.mappingSource().toString());

        ParsedDocument doc = mapper.parse(new SourceToParse("test", "1", BytesReference
                .bytes(XContentFactory.jsonBuilder()
                        .startObject()
                        .field("field", 1457654400)
                        .endObject()),
                XContentType.JSON));

        IndexableField[] fields = doc.rootDoc().getFields("field");
        assertEquals(2, fields.length);
        IndexableField pointField = fields[0];
        assertEquals(1457654400000L, pointField.numericValue().longValue());
    }

    public void testChangeLocale() throws IOException {
        assumeTrue("need java 9 for testing ",JavaVersion.current().compareTo(JavaVersion.parse("9")) >= 0);
        String mapping = Strings.toString(XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("properties").startObject("field").field("type", "date")
                    .field("format", "E, d MMM yyyy HH:mm:ss Z")
                    .field("locale", "de")
            .endObject().endObject().endObject().endObject());

        DocumentMapper mapper = parser.parse("type", new CompressedXContent(mapping));

        assertEquals(mapping, mapper.mappingSource().toString());

        mapper.parse(new SourceToParse("test", "1", BytesReference
                .bytes(XContentFactory.jsonBuilder()
                        .startObject()
                        .field("field", "Mi, 06 Dez 2000 02:55:00 -0800")
                        .endObject()),
                XContentType.JSON));
    }

    public void testNullValue() throws IOException {
        String mapping = Strings.toString(XContentFactory.jsonBuilder().startObject()
                .startObject("type")
                    .startObject("properties")
                        .startObject("field")
                            .field("type", "date")
                        .endObject()
                    .endObject()
                .endObject().endObject());

        DocumentMapper mapper = parser.parse("type", new CompressedXContent(mapping));
        assertEquals(mapping, mapper.mappingSource().toString());

        ParsedDocument doc = mapper.parse(new SourceToParse("test", "1", BytesReference
                .bytes(XContentFactory.jsonBuilder()
                        .startObject()
                        .nullField("field")
                        .endObject()),
                XContentType.JSON));
        assertArrayEquals(new IndexableField[0], doc.rootDoc().getFields("field"));

        mapping = Strings.toString(XContentFactory.jsonBuilder().startObject()
                .startObject("type")
                    .startObject("properties")
                        .startObject("field")
                            .field("type", "date")
                            .field("null_value", "2016-03-11")
                        .endObject()
                    .endObject()
                .endObject().endObject());

        mapper = parser.parse("type", new CompressedXContent(mapping));
        assertEquals(mapping, mapper.mappingSource().toString());

        doc = mapper.parse(new SourceToParse("test", "1", BytesReference
                .bytes(XContentFactory.jsonBuilder()
                        .startObject()
                        .nullField("field")
                        .endObject()),
                XContentType.JSON));
        IndexableField[] fields = doc.rootDoc().getFields("field");
        assertEquals(2, fields.length);
        IndexableField pointField = fields[0];
        assertEquals(1, pointField.fieldType().pointIndexDimensionCount());
        assertEquals(8, pointField.fieldType().pointNumBytes());
        assertFalse(pointField.fieldType().stored());
        assertEquals(1457654400000L, pointField.numericValue().longValue());
        IndexableField dvField = fields[1];
        assertEquals(DocValuesType.SORTED_NUMERIC, dvField.fieldType().docValuesType());
        assertEquals(1457654400000L, dvField.numericValue().longValue());
        assertFalse(dvField.fieldType().stored());
    }

    public void testNullConfigValuesFail() throws MapperParsingException, IOException {
        String mapping = Strings.toString(XContentFactory.jsonBuilder().startObject()
                .startObject("type")
                    .startObject("properties")
                        .startObject("field")
                            .field("type", "date")
                            .field("format", (String) null)
                        .endObject()
                    .endObject()
                .endObject().endObject());

        Exception e = expectThrows(MapperParsingException.class, () -> parser.parse("type", new CompressedXContent(mapping)));
        assertEquals("[format] on mapper [field] of type [date] must not have a [null] value", e.getMessage());
    }

    public void testEmptyName() throws IOException {
        String mapping = Strings.toString(XContentFactory.jsonBuilder().startObject().startObject("type")
            .startObject("properties").startObject("").field("type", "date")
            .field("format", "epoch_second").endObject().endObject()
            .endObject().endObject());

        IllegalArgumentException e = expectThrows(IllegalArgumentException.class,
            () -> parser.parse("type", new CompressedXContent(mapping))
        );
        assertThat(e.getMessage(), containsString("name cannot be empty string"));
    }

    public void testTimeZoneParsing() throws Exception {
        final String timeZonePattern = "yyyy-MM-dd" + randomFrom("XXX", "[XXX]", "'['XXX']'");

        String mapping = Strings.toString(XContentFactory.jsonBuilder().startObject()
                .startObject("type")
                    .startObject("properties")
                        .startObject("field")
                            .field("type", "date")
                            .field("format", timeZonePattern)
                        .endObject()
                    .endObject()
                .endObject().endObject());

        DocumentMapper mapper = parser.parse("type", new CompressedXContent(mapping));
        assertEquals(mapping, mapper.mappingSource().toString());

        DateFormatter formatter = DateFormatter.forPattern(timeZonePattern);
        final ZoneId randomTimeZone = randomBoolean() ? ZoneId.of(randomFrom("UTC", "CET")) : randomZone();
        final ZonedDateTime randomDate = ZonedDateTime.of(2016, 3, 11, 0, 0, 0, 0, randomTimeZone);

        ParsedDocument doc = mapper.parse(new SourceToParse("test", "1", BytesReference
                .bytes(XContentFactory.jsonBuilder()
                        .startObject()
                            .field("field", formatter.format(randomDate))
                        .endObject()),
                XContentType.JSON));

        IndexableField[] fields = doc.rootDoc().getFields("field");
        assertEquals(2, fields.length);

        long millis = randomDate.withZoneSameInstant(ZoneOffset.UTC).toInstant().toEpochMilli();
        assertEquals(millis, fields[0].numericValue().longValue());
    }

    public void testMergeDate() throws IOException {
        String initMapping = Strings.toString(XContentFactory.jsonBuilder().startObject().startObject("movie")
            .startObject("properties")
            .startObject("release_date").field("type", "date").field("format", "yyyy/MM/dd").endObject()
            .endObject().endObject().endObject());
        indexService.mapperService().merge("movie", new CompressedXContent(initMapping),
            MapperService.MergeReason.MAPPING_UPDATE);

        assertThat(indexService.mapperService().fieldType("release_date"), notNullValue());
        assertFalse(indexService.mapperService().fieldType("release_date")
            .getTextSearchInfo().isStored());

        String updateFormatMapping = Strings.toString(XContentFactory.jsonBuilder().startObject().startObject("movie")
            .startObject("properties")
            .startObject("release_date").field("type", "date").field("format", "epoch_millis").endObject()
            .endObject().endObject().endObject());

        Exception e = expectThrows(IllegalArgumentException.class,
            () -> indexService.mapperService().merge("movie", new CompressedXContent(updateFormatMapping),
                MapperService.MergeReason.MAPPING_UPDATE));
        assertThat(e.getMessage(), containsString("parameter [format] from [yyyy/MM/dd] to [epoch_millis]"));
    }

    public void testMergeText() throws Exception {
        String mapping = Strings.toString(XContentFactory.jsonBuilder().startObject().startObject("_doc")
                .startObject("properties").startObject("date").field("type", "date").endObject()
                .endObject().endObject().endObject());
        DocumentMapper mapper = indexService.mapperService().parse("_doc", new CompressedXContent(mapping));

        String mappingUpdate = Strings.toString(XContentFactory.jsonBuilder().startObject().startObject("_doc")
                .startObject("properties").startObject("date").field("type", "text").endObject()
                .endObject().endObject().endObject());
        DocumentMapper update = indexService.mapperService().parse("_doc", new CompressedXContent(mappingUpdate));

        IllegalArgumentException e = expectThrows(IllegalArgumentException.class,
                () -> mapper.merge(update.mapping(), MergeReason.MAPPING_UPDATE));
        assertEquals("mapper [date] cannot be changed from type [date] to [text]", e.getMessage());
    }

    public void testIllegalFormatField() throws Exception {
        String mapping =  Strings.toString(XContentFactory.jsonBuilder()
            .startObject()
                .startObject("type")
                    .startObject("properties")
                        .startObject("field")
                            .field("type", "date")
                            .array("format", "test_format")
                        .endObject()
                    .endObject()
                .endObject()
            .endObject());

        IllegalArgumentException e = expectThrows(IllegalArgumentException.class,
                () -> parser.parse("type", new CompressedXContent(mapping)));
        assertEquals("Invalid format: [[test_format]]: Unknown pattern letter: t", e.getMessage());
    }

    public void testMeta() throws Exception {
        String mapping = Strings.toString(XContentFactory.jsonBuilder().startObject().startObject("_doc")
                .startObject("properties").startObject("field").field("type", "date")
                .field("meta", Collections.singletonMap("foo", "bar"))
                .endObject().endObject().endObject().endObject());

        DocumentMapper mapper = indexService.mapperService().merge("_doc",
                new CompressedXContent(mapping), MergeReason.MAPPING_UPDATE);
        assertEquals(mapping, mapper.mappingSource().toString());

        String mapping2 = Strings.toString(XContentFactory.jsonBuilder().startObject().startObject("_doc")
                .startObject("properties").startObject("field").field("type", "date")
                .endObject().endObject().endObject().endObject());
        mapper = indexService.mapperService().merge("_doc",
                new CompressedXContent(mapping2), MergeReason.MAPPING_UPDATE);
        assertEquals(mapping2, mapper.mappingSource().toString());

        String mapping3 = Strings.toString(XContentFactory.jsonBuilder().startObject().startObject("_doc")
                .startObject("properties").startObject("field").field("type", "date")
                .field("meta", Collections.singletonMap("baz", "quux"))
                .endObject().endObject().endObject().endObject());
        mapper = indexService.mapperService().merge("_doc",
                new CompressedXContent(mapping3), MergeReason.MAPPING_UPDATE);
        assertEquals(mapping3, mapper.mappingSource().toString());
    }

    public void testParseSourceValue() {
        DateFieldMapper mapper = createMapper(Resolution.MILLISECONDS, null);
        String date = "2020-05-15T21:33:02.000Z";
        assertEquals(date, mapper.parseSourceValue(date, null));
        assertEquals(date, mapper.parseSourceValue(1589578382000L, null));

        DateFieldMapper mapperWithFormat = createMapper(Resolution.MILLISECONDS, "yyyy/MM/dd||epoch_millis");
        String dateInFormat = "1990/12/29";
        assertEquals(dateInFormat, mapperWithFormat.parseSourceValue(dateInFormat, null));
        assertEquals(dateInFormat, mapperWithFormat.parseSourceValue(662428800000L, null));

        DateFieldMapper mapperWithMillis = createMapper(Resolution.MILLISECONDS, "epoch_millis");
        String dateInMillis = "662428800000";
        assertEquals(dateInMillis, mapperWithMillis.parseSourceValue(dateInMillis, null));
        assertEquals(dateInMillis, mapperWithMillis.parseSourceValue(662428800000L, null));

        String nullValueDate = "2020-05-15T21:33:02.000Z";
        DateFieldMapper nullValueMapper = createMapper(Resolution.MILLISECONDS, null, nullValueDate);
        SourceLookup sourceLookup = new SourceLookup();
        sourceLookup.setSource(Collections.singletonMap("field", null));
        assertEquals(List.of(nullValueDate), nullValueMapper.lookupValues(sourceLookup, null));
    }

    public void testParseSourceValueWithFormat() {
        DateFieldMapper mapper = createMapper(Resolution.NANOSECONDS, "strict_date_time", "1970-12-29T00:00:00.000Z");
        String date = "1990-12-29T00:00:00.000Z";
        assertEquals("1990/12/29", mapper.parseSourceValue(date, "yyyy/MM/dd"));
        assertEquals("662428800000", mapper.parseSourceValue(date, "epoch_millis"));

        SourceLookup sourceLookup = new SourceLookup();
        sourceLookup.setSource(Collections.singletonMap("field", null));
        assertEquals(List.of("1970/12/29"), mapper.lookupValues(sourceLookup, "yyyy/MM/dd"));
    }

    public void testParseSourceValueNanos() {
        DateFieldMapper mapper = createMapper(Resolution.NANOSECONDS, "strict_date_time||epoch_millis");
        String date = "2020-05-15T21:33:02.123456789Z";
        assertEquals("2020-05-15T21:33:02.123456789Z", mapper.parseSourceValue(date, null));
        assertEquals("2020-05-15T21:33:02.123Z", mapper.parseSourceValue(1589578382123L, null));

        String nullValueDate = "2020-05-15T21:33:02.123456789Z";
        DateFieldMapper nullValueMapper = createMapper(Resolution.NANOSECONDS, "strict_date_time||epoch_millis", nullValueDate);
        SourceLookup sourceLookup = new SourceLookup();
        sourceLookup.setSource(Collections.singletonMap("field", null));
        assertEquals(List.of(nullValueDate), nullValueMapper.lookupValues(sourceLookup, null));
    }

    private DateFieldMapper createMapper(Resolution resolution, String format) {
        return createMapper(resolution, format, null);
    }

    private DateFieldMapper createMapper(Resolution resolution, String format, String nullValue) {
        Settings settings = Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT.id).build();
        Mapper.BuilderContext context = new Mapper.BuilderContext(settings, new ContentPath());

        Map<String, Object> mapping = new HashMap<>();
        mapping.put("type", "date_nanos");
        if (format != null) {
            mapping.put("format", format);
        }
        if (nullValue != null) {
            mapping.put("null_value", nullValue);
        }

        DateFieldMapper.Builder builder = new DateFieldMapper.Builder("field", resolution, null, false);
        builder.parse("field", null, mapping);
        return builder.build(context);
    }
}
