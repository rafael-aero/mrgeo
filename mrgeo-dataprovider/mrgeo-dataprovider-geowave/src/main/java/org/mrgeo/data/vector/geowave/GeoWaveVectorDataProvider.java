package org.mrgeo.data.vector.geowave;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

import com.vividsolutions.jts.io.ParseException;
import com.vividsolutions.jts.io.WKTReader;
import mil.nga.giat.geowave.accumulo.AccumuloOperations;
import mil.nga.giat.geowave.accumulo.BasicAccumuloOperations;
import mil.nga.giat.geowave.accumulo.metadata.AccumuloAdapterStore;
import mil.nga.giat.geowave.accumulo.metadata.AccumuloIndexStore;
import mil.nga.giat.geowave.index.ByteArrayId;
import mil.nga.giat.geowave.store.CloseableIterator;
import mil.nga.giat.geowave.store.adapter.AdapterStore;
import mil.nga.giat.geowave.store.adapter.DataAdapter;
import mil.nga.giat.geowave.store.adapter.statistics.CountDataStatistics;
import mil.nga.giat.geowave.store.adapter.statistics.DataStatisticsStore;
import mil.nga.giat.geowave.store.dimension.DimensionField;
import mil.nga.giat.geowave.store.index.CommonIndexValue;
import mil.nga.giat.geowave.store.index.Index;
import mil.nga.giat.geowave.store.query.BasicQuery;
import mil.nga.giat.geowave.store.query.Query;
import mil.nga.giat.geowave.vector.AccumuloDataStatisticsStoreExt;
import mil.nga.giat.geowave.vector.VectorDataStore;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.geotools.filter.text.cql2.CQLException;
import org.geotools.filter.text.ecql.ECQL;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.mrgeo.data.ProviderProperties;
import org.mrgeo.data.vector.VectorDataProvider;
import org.mrgeo.data.vector.VectorInputFormatContext;
import org.mrgeo.data.vector.VectorInputFormatProvider;
import org.mrgeo.data.vector.VectorMetadataReader;
import org.mrgeo.data.vector.VectorMetadataWriter;
import org.mrgeo.data.vector.VectorOutputFormatContext;
import org.mrgeo.data.vector.VectorOutputFormatProvider;
import org.mrgeo.data.vector.VectorReader;
import org.mrgeo.data.vector.VectorReaderContext;
import org.mrgeo.data.vector.VectorWriter;
import org.mrgeo.data.vector.VectorWriterContext;
import org.mrgeo.geometry.Geometry;
import org.opengis.filter.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GeoWaveVectorDataProvider extends VectorDataProvider
{
  static Logger log = LoggerFactory.getLogger(GeoWaveVectorDataProvider.class);

  private static final String PROVIDER_PROPERTIES_SIZE = GeoWaveVectorDataProvider.class.getName() + "providerProperties.size";
  private static final String PROVIDER_PROPERTIES_KEY_PREFIX = GeoWaveVectorDataProvider.class.getName() + "providerProperties.key";
  private static final String PROVIDER_PROPERTIES_VALUE_PREFIX = GeoWaveVectorDataProvider.class.getName() + "providerProperties.value";

  private static GeoWaveConnectionInfo connectionInfo;
  private static AccumuloOperations storeOperations;
  private static AdapterStore adapterStore;
  private static DataStatisticsStore statisticsStore;
  private static VectorDataStore dataStore;
  private static Index index;

  // Package private for unit testing
  static boolean initialized = false;

  private DataAdapter<?> dataAdapter;
  private Filter filter;
  private String cqlFilter;
  private com.vividsolutions.jts.geom.Geometry spatialConstraint;
  private Date startTimeConstraint;
  private Date endTimeConstraint;
  private GeoWaveVectorMetadataReader metaReader;
  private ProviderProperties providerProperties;

  private static class ParseResults
  {
    public String name;
    public Map<String, String> settings = new HashMap<String, String>();
  }

  public GeoWaveVectorDataProvider(String inputPrefix, String input, ProviderProperties providerProperties)
  {
    super(inputPrefix, input);
    // This constructor is only called from driver-side (i.e. not in
    // map/reduce tasks), so the connection settings are obtained from
    // the mrgeo.conf file.
    getConnectionInfo(); // initializes connectionInfo if needed
    this.providerProperties = providerProperties;
  }

  public static GeoWaveConnectionInfo getConnectionInfo()
  {
    if (connectionInfo == null)
    {
      connectionInfo = GeoWaveConnectionInfo.load();
    }
    return connectionInfo;
  }

  static void setConnectionInfo(GeoWaveConnectionInfo connInfo)
  {
    connectionInfo = connInfo;
  }

  public static DataStatisticsStore getStatisticsStore() throws AccumuloSecurityException, AccumuloException, IOException
  {
    initDataSource();
    return statisticsStore;
  }

  public static Index getIndex() throws AccumuloSecurityException, AccumuloException, IOException
  {
    initDataSource();
    return index;
  }

  public String getGeoWaveResourceName() throws IOException
  {
    ParseResults results = parseResourceName(getResourceName());
    return results.name;
  }

  public DataAdapter<?> getDataAdapter() throws AccumuloSecurityException, AccumuloException, IOException
  {
    init();
    return dataAdapter;
  }

  /**
   * Returns the CQL filter for this data provider. If no filtering is required, then this will
   * return a null value.
   *
   * @return
   * @throws AccumuloSecurityException
   * @throws AccumuloException
   * @throws IOException
   */
  public String getCqlFilter() throws AccumuloSecurityException, AccumuloException, IOException
  {
    init();
    return cqlFilter;
  }

  public com.vividsolutions.jts.geom.Geometry getSpatialConstraint() throws AccumuloSecurityException, AccumuloException, IOException
  {
    init();
    return spatialConstraint;
  }

  public Date getStartTimeConstraint() throws AccumuloSecurityException, AccumuloException, IOException
  {
    init();
    return startTimeConstraint;
  }

  public Date getEndTimeConstraint() throws AccumuloSecurityException, AccumuloException, IOException
  {
    init();
    return endTimeConstraint;
  }

  /**
   * Parses the input string into the name of the input and the optional query string
   * that accompanies it. The two strings are separated by a semi-colon, and the query
   * should be included in double quotes if it contains any semi-colons or square brackets.
   * But the double quotes are not required otherwise.
   *
   * @param input
   * @return
   */
  private static ParseResults parseResourceName(String input) throws IOException
  {
    int semiColonIndex = input.indexOf(';');
    if (semiColonIndex == 0)
    {
      throw new IOException("Missing name from GeoWave data source: " + input);
    }
    ParseResults results = new ParseResults();
    if (semiColonIndex > 0)
    {
      results.name = input.substring(0, semiColonIndex);
      // Start parsing the data source settings
      String strSettings = input.substring(semiColonIndex + 1);
      // Now parse each property of the geowave source.
      parseDataSourceSettings(strSettings, results.settings);
    }
    else
    {
      results.name = input;
    }
    return results;
  }

  // Package private for unit testing
  static void parseDataSourceSettings(String strSettings,
                                              Map<String, String> settings) throws IOException
  {
    boolean foundSemiColon = true;
    String remaining = strSettings.trim();
    if (remaining.isEmpty())
    {
      return;
    }

    int settingIndex = 0;
    while (foundSemiColon)
    {
      int equalsIndex = remaining.indexOf("=", settingIndex);
      if (equalsIndex >= 0)
      {
        String keyName = remaining.substring(settingIndex, equalsIndex).trim();
        // Everything after the = char
        remaining = remaining.substring(equalsIndex + 1).trim();
        if (remaining.length() > 0)
        {
          // Handle double-quoted settings specially, skipping escaped double
          // quotes inside the value.
          if (remaining.startsWith("\""))
          {
            // Find the index of the corresponding closing quote. Note that double
            // quotes can be escaped with a backslash (\) within the quoted string.
            int closingQuoteIndex = remaining.indexOf('"', 1);
            while (closingQuoteIndex > 0)
            {
              // If the double quote is not preceeded by an escape backslash,
              // then we've found the closing quote.
              if (remaining.charAt(closingQuoteIndex - 1) != '\\')
              {
                break;
              }
              closingQuoteIndex = remaining.indexOf('"', closingQuoteIndex + 1);
            }
            if (closingQuoteIndex >= 0)
            {
              String value = remaining.substring(1, closingQuoteIndex);
              log.debug("Adding GeoWave source key setting " + keyName + " = " + value);
              settings.put(keyName, value);
              settingIndex = 0;
              int nextSemiColonIndex = remaining.indexOf(';', closingQuoteIndex + 1);
              if (nextSemiColonIndex >= 0)
              {
                foundSemiColon = true;
                remaining = remaining.substring(nextSemiColonIndex + 1).trim();
              }
              else
              {
                // No more settings
                foundSemiColon = false;
              }
            }
            else
            {
              throw new IOException("Invalid GeoWave settings string, expected ending double quote for key " +
                                    keyName + " in " + strSettings);
            }
          }
          else
          {
            // The value is not quoted
            int semiColonIndex = remaining.indexOf(";");
            if (semiColonIndex >= 0)
            {
              String value = remaining.substring(0, semiColonIndex);
              log.debug("Adding GeoWave source key setting " + keyName + " = " + value);
              settings.put(keyName, value);
              settingIndex = 0;
              remaining = remaining.substring(semiColonIndex + 1);
            }
            else
            {
              log.debug("Adding GeoWave source key setting " + keyName + " = " + remaining);
              settings.put(keyName, remaining);
              // There are no more settings since there are no more semi-colons
              foundSemiColon = false;
            }
          }
        }
        else
        {
          throw new IOException("Missing value for " + keyName);
        }
      }
      else
      {
        throw new IOException("Invalid syntax. No value assignment in \"" + remaining + "\"");
      }
    }
  }

  @Override
  public VectorMetadataReader getMetadataReader()
  {
    if (metaReader == null)
    {
      metaReader = new GeoWaveVectorMetadataReader(this);
    }
    return metaReader;
  }

  @Override
  public VectorMetadataWriter getMetadataWriter()
  {
    // Not yet implemented
    return null;
  }

  @Override
  public VectorReader getVectorReader() throws IOException
  {
    try
    {
      init();
    }
    catch (AccumuloSecurityException e)
    {
      throw new IOException("AccumuloSecurityException in GeoWave data provider getVectorReader", e);
    }
    catch (AccumuloException e)
    {
      throw new IOException("AccumuloException in GeoWave data provider getVectorReader", e);
    }
    Query query = new BasicQuery(new BasicQuery.Constraints());
    GeoWaveVectorReader reader = new GeoWaveVectorReader(dataStore,
        adapterStore.getAdapter(new ByteArrayId(this.getGeoWaveResourceName())),
        query, index, filter, providerProperties);
    return reader;
  }

  @Override
  public VectorReader getVectorReader(VectorReaderContext context) throws IOException
  {
    return getVectorReader();
  }

  @Override
  public VectorWriter getVectorWriter()
  {
    // Not yet implemented
    return null;
  }

  @Override
  public VectorWriter getVectorWriter(VectorWriterContext context)
  {
    // Not yet implemented
    return null;
  }

  @Override
  public RecordReader<LongWritable, Geometry> getRecordReader()
  {
    return new GeoWaveVectorRecordReader();
  }

  @Override
  public RecordWriter<LongWritable, Geometry> getRecordWriter()
  {
    // Not yet implemented
    return null;
  }

  @Override
  public VectorInputFormatProvider getVectorInputFormatProvider(VectorInputFormatContext context)
  {
    return new GeoWaveVectorInputFormatProvider(context, this);
  }

  @Override
  public VectorOutputFormatProvider getVectorOutputFormatProvider(VectorOutputFormatContext context)
  {
    // Not yet implemented
    return null;
  }

  @Override
  public void delete() throws IOException
  {
    // Not yet implemented
  }

  @Override
  public void move(String toResource) throws IOException
  {
    // Not yet implemented
  }

  public static boolean isValid(Configuration conf)
  {
    initConnectionInfo(conf);
    return (connectionInfo != null);
  }

  public static boolean isValid()
  {
    initConnectionInfo();
    return (connectionInfo != null);
  }

  public static String[] listVectors(final ProviderProperties providerProperties) throws AccumuloException, AccumuloSecurityException, IOException
  {
    initConnectionInfo();
    initDataSource();
    List<String> results = new ArrayList<String>();
    CloseableIterator<DataAdapter<?>> iter = adapterStore.getAdapters();
    try
    {
      while (iter.hasNext())
      {
        DataAdapter<?> adapter = iter.next();
        if (adapter != null)
        {
          ByteArrayId adapterId = adapter.getAdapterId();
          if (checkAuthorizations(adapterId, providerProperties))
          {
            results.add(adapterId.getString());
          }
        }
      }
    }
    finally
    {
      if (iter != null)
      {
        iter.close();
      }
    }
    String[] resultArray = new String[results.size()];
    return results.toArray(resultArray);
  }

  public static boolean canOpen(String input, ProviderProperties providerProperties) throws AccumuloException, AccumuloSecurityException, IOException
  {
    initConnectionInfo();
    initDataSource();
    ParseResults results = parseResourceName(input);
    ByteArrayId adapterId = new ByteArrayId(results.name);
    DataAdapter<?> adapter = adapterStore.getAdapter(adapterId);
    if (adapter == null)
    {
      return false;
    }
    return checkAuthorizations(adapterId, providerProperties);
  }

  private static boolean checkAuthorizations(ByteArrayId adapterId,
      ProviderProperties providerProperties) throws IOException, AccumuloException, AccumuloSecurityException
  {
    // Check to see if the requester is authorized to see any of the data in
    // the adapter.
    return (getAdapterCount(adapterId, providerProperties) > 0L);
  }

  public static long getAdapterCount(ByteArrayId adapterId,
                                     ProviderProperties providerProperties)
          throws IOException, AccumuloException, AccumuloSecurityException
  {
    initConnectionInfo();
    initDataSource();
    List<String> roles = providerProperties.getRoles();
    if (roles != null && roles.size() > 0)
    {
      String auths = StringUtils.join(providerProperties.getRoles(), ",");
      CountDataStatistics<?> count = (CountDataStatistics<?>)statisticsStore.getDataStatistics(adapterId,  CountDataStatistics.STATS_ID, auths);
      if (count != null && count.isSet())
      {
        return count.getCount();
      }
    }
    else
    {
      CountDataStatistics<?> count = (CountDataStatistics<?>)statisticsStore.getDataStatistics(adapterId,  CountDataStatistics.STATS_ID);
      if (count != null && count.isSet())
      {
        return count.getCount();
      }
    }
    return 0L;
  }

  private void init() throws AccumuloSecurityException, AccumuloException, IOException
  {
    // Don't initialize more than once.
    if (initialized)
    {
      return;
    }
    initialized = true;
    // Extract the GeoWave adapter name and optional CQL string
    ParseResults results = parseResourceName(getResourceName());
    // Now perform initialization for this specific data provider (i.e. for
    // this resource).
    dataAdapter = adapterStore.getAdapter(new ByteArrayId(results.name));
    assignSettings(results.name, results.settings);

// Testing code
//    SimpleFeatureType sft = ((FeatureDataAdapter)dataAdapter).getType();
//    int attributeCount = sft.getAttributeCount();
//    System.out.println("attributeCount = " + attributeCount);
//    CloseableIterator<?> iter = dataStore.query(dataAdapter, null);
//    try
//    {
//      while (iter.hasNext())
//      {
//        Object value = iter.next();
//        System.out.println("class is " + value.getClass().getName());
//        System.out.println("value is " + value);
//      }
//    }
//    finally
//    {
//      iter.close();
//    }
  }

  // Package private for unit testing
  void assignSettings(String name, Map<String, String> settings) throws IOException
  {
    filter = null;
    spatialConstraint = null;
    startTimeConstraint = null;
    endTimeConstraint = null;
    for (String keyName : settings.keySet())
    {
      if (keyName != null && !keyName.isEmpty())
      {
        String value = settings.get(keyName);
        switch(keyName)
        {
          case "spatial":
          {
            WKTReader wktReader = new WKTReader();
            try
            {
              spatialConstraint = wktReader.read(value);
            }
            catch (ParseException e)
            {
              throw new IOException("Invalid WKT specified for spatial property of GeoWave data source " +
                                    name);
            }
            break;
          }

          case "startTime":
          {
            startTimeConstraint = parseDate(value);
            break;
          }

          case "endTime":
          {
            endTimeConstraint = parseDate(value);
            break;
          }

          case "cql":
          {
            if (value != null && !value.isEmpty())
            {
              cqlFilter = value;
              try
              {
                filter = ECQL.toFilter(value);
              }
              catch (CQLException e)
              {
                throw new IOException("Bad CQL filter: " + value, e);
              }
            }
            break;
          }
          default:
            throw new IOException("Unrecognized setting for GeoWave data source " +
                                  name + ": " + keyName);
        }
      }
    }

    if ((startTimeConstraint == null) != (endTimeConstraint == null))
    {
      throw new IOException("When querying a GeoWave data source by time," +
                            " both the start and the end are required.");
    }
    if (startTimeConstraint != null && endTimeConstraint != null && startTimeConstraint.after(endTimeConstraint))
    {
      throw new IOException("For GeoWave data source " + name + ", startDate must be after endDate");
    }
  }

  private Date parseDate(String value)
  {
    DateTimeFormatter formatter = ISODateTimeFormat.dateOptionalTimeParser();
    DateTime dateTime = formatter.parseDateTime(value);
    return dateTime.toDate();
  }

  private static void initConnectionInfo(Configuration conf)
  {
    // The connectionInfo only needs to be set once. It is the same for
    // the duration of the JVM. Note that it is instantiated differently
    // on the driver-side than it is within map/reduce tasks. This method
    // loads connection settings from the job configuration.
    if (connectionInfo == null)
    {
      connectionInfo = GeoWaveConnectionInfo.load(conf);
    }
  }

  private static void initConnectionInfo()
  {
    // The connectionInfo only needs to be set once. It is the same for
    // the duration of the JVM. Note that it is instantiated differently
    // on the driver-side than it is within map/reduce tasks. This method
    // loads connection settings from the mrgeo.conf file.
    if (connectionInfo == null)
    {
      connectionInfo = GeoWaveConnectionInfo.load();
    }
  }

  private static void initDataSource() throws AccumuloException, AccumuloSecurityException, IOException
  {
    if (storeOperations == null)
    {
      storeOperations = new BasicAccumuloOperations(
          connectionInfo.getZookeeperServers(),
          connectionInfo.getInstanceName(),
          connectionInfo.getUserName(),
          connectionInfo.getPassword(),
          connectionInfo.getNamespace());
      final AccumuloIndexStore indexStore = new AccumuloIndexStore(
          storeOperations);
  
      statisticsStore = new AccumuloDataStatisticsStoreExt(
          storeOperations);
      adapterStore = new AccumuloAdapterStore(
          storeOperations);
      dataStore = new VectorDataStore(
          indexStore,
          adapterStore,
          statisticsStore,
          storeOperations);
      CloseableIterator<Index> indices = dataStore.getIndices();
      try
      {
        if (indices.hasNext())
        {
          index = indices.next();
          if (log.isDebugEnabled())
          {
            log.debug("Found GeoWave index " + index.getId().getString());
            log.debug("  index type = " + index.getDimensionalityType().toString());
            DimensionField<? extends CommonIndexValue>[] dimFields = index.getIndexModel().getDimensions();
            for (DimensionField<? extends CommonIndexValue> dimField : dimFields)
            {
              log.debug("  Dimension field: " + dimField.getFieldId().getString());
            }
          }
        }
      }
      finally
      {
        indices.close();
      }
    }
  }
}
