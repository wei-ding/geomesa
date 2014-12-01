/*
 * Copyright 2014 Commonwealth Computer Research, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.locationtech.geomesa.core.index

import com.typesafe.scalalogging.slf4j.Logging
import com.vividsolutions.jts.geom.Point
import org.apache.accumulo.core.data.Key
import org.geotools.data.Query
import org.locationtech.geomesa.core.data._
import org.locationtech.geomesa.core.util._
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}

import scala.util.parsing.combinator.RegexParsers

// A secondary index consists of interleaved elements of a composite key stored in
// Accumulo's key (row, column family, and column qualifier)
//
// A spatio-temporal index utilizes the location and the time of an entry to construct
// the secondary index.
//
// An index schema consists of the mapping of the composite key (time,space) to the three
// elements of a Accumulo key.  The mapping is specified using the printf-like format string.
// The format string consists of an entry for each of the row, column family, and column
// qualifier.  The entry consists of formatting directives of the composite key.  A directive
// has the following form:
//
// %[formatting options]#[formatting code]
//
// The following format codes are available
// s    => the separator character
// r    => a random partitioner - creates shards on [0, option], that is
//         (option + 1) separate partitions
// gh   => geohash formatter - options are the start character and number of characters
// d    => date formatter - options are any properly formed date format string
// cstr => constant string formatter
//
// An entry consists of a sequence of formatting directives with and must start with the
// separator directive.  For example, the following entry format:
//
// %~#s%999#r%0,4#gh%HHmm#d
//
// specifies that the separator character is a '~', then a random partition element between 000
// and 999, then the first four characters of the geohash, then the hours and minutes of the time
// of the entry.  The resulting Accumulo key element might look like "342~tmw1~1455"
//
// A full schema consists of 3 entry formatting directives separated by '::'.  The following is
// an example of a fully specified index schema:
//
// %~#s%999#r%0,4#gh%HHmm#d::%~#s%4,2#gh::%~#s%6,1#gh%yyyyMMdd#d

case class IndexSchema(encoder: IndexEntryEncoder,
                       decoder: IndexEntryDecoder,
                       planner: QueryPlanner,
                       featureType: SimpleFeatureType) extends ExplainingLogging {

  def encode(entry: SimpleFeature, visibility: String = "") = encoder.encode(entry, visibility)
  def decode(key: Key): SimpleFeature = decoder.decode(key)


  // utility method to ask for the maximum allowable shard number
  def maxShard: Int =
    encoder.rowf match {
      case CompositeTextFormatter(Seq(PartitionTextFormatter(numPartitions), xs@_*), sep) => numPartitions
      case _ => 1  // couldn't find a matching partitioner
    }

  // Writes out an explanation of how a query would be run.
  def explainQuery(q: Query, output: ExplainerOutputType = log) = {
     planner.getIterator(new ExplainingConnectorCreator(output), featureType, q, output)
  }
}

object IndexSchema extends SchemaHelpers with Logging {

  // a key element consists of a separator and any number of random partitions, geohashes, and dates
  def keypart: Parser[CompositeTextFormatter] =
    (sep ~ rep(randEncoder | geohashEncoder | dateEncoder | constantStringEncoder | resolutionEncoder | bandEncoder)) ^^
    {
      case sep ~ xs => CompositeTextFormatter(xs, sep)
    }

  // the column qualifier must end with an ID-encoder
  def cqpart: Parser[CompositeTextFormatter] =
    phrase(sep ~ rep(randEncoder | geohashEncoder | dateEncoder | constantStringEncoder) ~ idEncoder) ^^ {
      case sep ~ xs ~ id => CompositeTextFormatter(xs :+ id, sep)
    }

  // An index key is three keyparts, one for row, colf, and colq
  def formatter = keypart ~ PART_DELIMITER ~ keypart ~ PART_DELIMITER ~ cqpart ^^ {
    case rowf ~ PART_DELIMITER ~ cff ~ PART_DELIMITER ~ cqf => (rowf, cff, cqf)
  }

  // builds the encoder from a string representation
  def buildKeyEncoder(s: String, featureEncoder: SimpleFeatureEncoder): IndexEntryEncoder = {
    val (rowf, cff, cqf) = parse(formatter, s).get
    IndexEntryEncoder(rowf, cff, cqf, featureEncoder)
  }

  // builds the date decoder to deserialize the entire date from the parts of the index key
  def dateDecoderParser = keypart ~ PART_DELIMITER ~ keypart ~ PART_DELIMITER ~ cqpart ^^ {
    case rowf ~ PART_DELIMITER ~ cff ~ PART_DELIMITER ~ cqf => {
      // extract the per-key-portion date encoders; each is optional
      val rowVals: Option[(String,Int)] = extractDateEncoder(rowf.lf, 0, rowf.sep.length)
      val cfVals: Option[(String,Int)] = extractDateEncoder(cff.lf, 0, cff.sep.length)
      val cqVals: Option[(String,Int)] = extractDateEncoder(cqf.lf, 0, cqf.sep.length)

      // build a non-None list of these date extractors
      val netVals : Iterable[(AbstractExtractor,String)] =
        rowVals.map(_ match { case (f,offset) => { (RowExtractor(offset, f.length), f)}}) ++
        cfVals.map(_ match { case (f,offset) => { (ColumnFamilyExtractor(offset, f.length), f)}}) ++
        cqVals.map(_ match { case (f,offset) => { (ColumnQualifierExtractor(offset, f.length), f)}})

      // consolidate this into a single extractor-sequence and date format
      val consolidatedVals: (Seq[AbstractExtractor],String) = netVals.
        foldLeft((List[AbstractExtractor](),""))((t1,t2) => t1 match { case (extractors,fs) =>
          t2 match { case (extractor,f) => (extractors ++ List(extractor), fs + f)
      }})

      // issue:  not all schema contain a date-portion;
      // for those that do, you have already parsed it;
      // for those that do not, you must return None
      consolidatedVals match {
        case (extractors,fs) if (!extractors.isEmpty) => Some(DateDecoder(extractors, fs))
        case _ => None
      }
  }}

  def buildDateDecoder(s: String): Option[DateDecoder[AbstractExtractor]] = parse(dateDecoderParser, s).get

  // builds a geohash decoder to extract the entire geohash from the parts of the index key
  def ghDecoderParser = keypart ~ PART_DELIMITER ~ keypart ~ PART_DELIMITER ~ cqpart ^^ {
    case rowf ~ PART_DELIMITER ~ cff ~ PART_DELIMITER ~ cqf => {
      val (roffset, (ghoffset, rbits)) = extractGeohashEncoder(rowf.lf, 0, rowf.sep.length)
      val (cfoffset, (ghoffset2, cfbits)) = extractGeohashEncoder(cff.lf, 0, cff.sep.length)
      val (cqoffset, (ghoffset3, cqbits)) = extractGeohashEncoder(cqf.lf, 0, cqf.sep.length)
      val l = List((ghoffset, RowExtractor(roffset, rbits)),
        (ghoffset2, ColumnFamilyExtractor(cfoffset, cfbits)),
        (ghoffset3, ColumnQualifierExtractor(cqoffset, cqbits)))
      GeohashDecoder(l.sortBy { case (off, _) => off }.map { case (_, e) => e })
    }
  }

  def buildGeohashDecoder(s: String): GeohashDecoder = parse(ghDecoderParser, s).get

  def idDecoderParser = keypart ~ PART_DELIMITER ~ keypart ~ PART_DELIMITER ~ cqpart ^^ {
    case rowf ~ PART_DELIMITER ~ cff ~ PART_DELIMITER ~ cqf => {
      val bits = extractIdEncoder(cqf.lf, 0, cqf.sep.length)
      IdDecoder(Seq(ColumnQualifierExtractor(0, bits)))
    }
  }

  def buildIdDecoder(s: String) = parse(idDecoderParser, s).get

  def geohashKeyPlanner: Parser[GeoHashKeyPlanner] = geohashPattern ^^ {
    case o ~ b => GeoHashKeyPlanner(o, b)
  }

  def resolutionKeyPlanner: Parser[ResolutionPlanner] = resolutionPattern ^^ {
    case d => ResolutionPlanner(lexiDecodeStringToDouble(d))
  }

  def bandKeyPlanner: Parser[BandPlanner] = bandPattern ^^ {
    case b => BandPlanner(b)
  }

  def keyPlanner: Parser[KeyPlanner] =
    sep ~ rep(constStringPlanner | datePlanner | randPartitionPlanner | geohashKeyPlanner | resolutionKeyPlanner | bandKeyPlanner) <~ "::.*".r ^^ {
      case sep ~ list => CompositePlanner(list, sep)
    }

  def buildKeyPlanner(s: String) = parse(keyPlanner, s) match {
    case Success(result, _) => result
    case fail: NoSuccess => throw new Exception(fail.msg)
  }

  def geohashColumnFamilyPlanner: Parser[GeoHashColumnFamilyPlanner] = (keypart ~ PART_DELIMITER) ~> (sep ~ rep(randEncoder | geohashEncoder | dateEncoder | constantStringEncoder | bandKeyPlanner)) <~ (PART_DELIMITER ~ keypart) ^^ {
    case sep ~ xs => xs.find(tf => tf match {
      case gh: GeoHashTextFormatter => true
      case _ => false
    }).map(ghtf => ghtf match {
      case GeoHashTextFormatter(o, n) => GeoHashColumnFamilyPlanner(o,n)
    }).get
  }

  def buildColumnFamilyPlanner(s: String): ColumnFamilyPlanner = parse(geohashColumnFamilyPlanner, s) match {
    case Success(result, _) => result
    case fail: NoSuccess => throw new Exception(fail.msg)
  }

  // only those geometries known to contain only point data can guarantee that
  // they do not contain duplicates
  def mayContainDuplicates(featureType: SimpleFeatureType): Boolean =
    try {
      featureType == null || featureType.getGeometryDescriptor.getType.getBinding != classOf[Point]
    } catch {
      case e: Exception =>
        logger.warn(s"Error comparing default geometry for feature type ${featureType.getName}")
        true
    }

  // builds a IndexSchema (requiring a feature type)
  def apply(s: String,
            featureType: SimpleFeatureType,
            featureEncoder: SimpleFeatureEncoder): IndexSchema = {
    val keyEncoder        = buildKeyEncoder(s, featureEncoder)
    val geohashDecoder    = buildGeohashDecoder(s)
    val dateDecoder       = buildDateDecoder(s)
    val keyPlanner        = buildKeyPlanner(s)
    val cfPlanner         = buildColumnFamilyPlanner(s)
    val indexEntryDecoder = IndexEntryDecoder(geohashDecoder, dateDecoder)
    val queryPlanner      = QueryPlanner(s, featureType, featureEncoder.encoding)
    IndexSchema(keyEncoder, indexEntryDecoder, queryPlanner, featureType)
  }

  def getIndexEntryDecoder(s: String) = {
    val geohashDecoder    = buildGeohashDecoder(s)
    val dateDecoder       = buildDateDecoder(s)
    IndexEntryDecoder(geohashDecoder, dateDecoder)
  }
}

/**
 * Class to facilitate the building of custom index schemas.
 *
 * @param separator
 */
class IndexSchemaBuilder(separator: String) {

  import org.locationtech.geomesa.core.index.IndexSchema._

  var newPart = true
  val schema = new StringBuilder()

  /**
   * Adds a random number, useful for sharding.
   *
   * @param maxValue
   * @return the schema builder instance
   */
  def randomNumber(maxValue: Int): IndexSchemaBuilder = append(RANDOM_CODE, maxValue)

  /**
   * Adds a constant value.
   *
   * @param constant
   * @return the schema builder instance
   */
  def constant(constant: String): IndexSchemaBuilder = append(CONSTANT_CODE, constant)

  /**
   * Adds a date value.
   *
   * @param format format to apply to the date, equivalent to SimpleDateFormat
   * @return the schema builder instance
   */
  def date(format: String): IndexSchemaBuilder = append(DATE_CODE, format)

  /**
   * Adds a geohash value.
   *
   * @param offset
   * @param length
   * @return the schema builder instance
   */
  def geoHash(offset: Int, length: Int): IndexSchemaBuilder = append(GEO_HASH_CODE, offset, ',', length)

  /**
   * Add an ID value.
   *
   * @return the schema builder instance
   */
  def id(): IndexSchemaBuilder = id(-1)

  /**
   * Add an ID value.
   *
   * @param length ID will be padded to this length
   * @return the schema builder instance
   */
  def id(length: Int): IndexSchemaBuilder = {
    if (length > 0) {
      append(ID_CODE, length)
    } else {
      append(ID_CODE)
    }
  }

  /**
   * Add an Raster Resolution Value
   *
   * @return the schema builder instance
   */
  def resolution(): IndexSchemaBuilder = append(RESOLUTION_CODE, 0.0)

  /**
   * Adds a resolution value.
   *
   * @param res
   * @return the schema builder instance
   */
  def resolution(res: Double): IndexSchemaBuilder = append(RESOLUTION_CODE, lexiEncodeDoubleToString(res))

  /**
   * Add a Band Value
   *
   * @param band
   * @return the schema builder instance
   */
  def band(band: String): IndexSchemaBuilder = append(BAND_CODE, band)

  /**
   * End the current part of the schema format. Schemas consist of (in order) key part, column
   * family part and column qualifier part. The schema builder starts on the key part.
   *
   * The schema builder does not validate parts. This method should be called exactly two times to
   * build a typical schema.
   *
   * @return the schema builder instance
   */
  def nextPart(): IndexSchemaBuilder = {
    schema.append(PART_DELIMITER)
    newPart = true
    this
  }

  /**
   *
   * @return the formatted schema string
   */
  def build(): String = schema.toString()

  override def toString(): String = build

  /**
   * Clears internal state
   */
  def reset(): Unit = {
    schema.clear()
    newPart = true
  }

  /**
   * Wraps the code in the appropriate delimiters and adds the provided values
   *
   * @param code
   * @param values
   * @return
   */
  private def append(code: String, values: Any*): IndexSchemaBuilder = {
    if (newPart) {
      schema.append(CODE_START).append(separator).append(CODE_END).append(SEPARATOR_CODE)
      newPart = false
    }
    schema.append(CODE_START)
    values.foreach(schema.append(_))
    schema.append(CODE_END).append(code)
    this
  }
}
