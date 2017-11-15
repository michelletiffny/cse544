import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.*;

import scala.Tuple2;

import java.util.Comparator;
import java.io.Serializable;
import java.util.Collections;

class DummyComparator implements Serializable, Comparator<Tuple2<String, Integer>> 
{
  @Override
  public int compare(Tuple2<String, Integer> a, Tuple2<String, Integer> b) 
    {
    return Integer.compare(a._2(), b._2());
    }
}

public class HW6 {

  // the full input data file is at s3://us-east-1.elasticmapreduce.samples/flightdata/input

  /*
    You are free to change the contents of main as much as you want. We will be running a separate main for
     grading purposes.
   */
  public static void main(String[] args) {

    if (args.length < 2)
      throw new RuntimeException("Usage: HW6 <datafile location> <output location>");

    String dataFile = args[0];
    String output = args[1];

    // turn off logging except for error messages
    Logger.getLogger("org.apache.spark").setLevel(Level.ERROR);
    Logger.getLogger("org.apache.spark.storage.BlockManager").setLevel(Level.ERROR);

    // use this for running locally
    //SparkSession spark = SparkSession.builder().appName("HW6").config("spark.master", "local").getOrCreate();

    // use this for running on ec2
    SparkSession spark = SparkSession.builder().appName("HW6").getOrCreate();

    Dataset<Row> r = warmup(spark, dataFile);
    r.javaRDD().repartition(1).saveAsTextFile(output+"/warmup/");
    // uncomment each of the below to run your solutions

    /* Problem 1 */
    Dataset<Row> r1 = Q1(spark, dataFile);

    // collect all outputs from different machines to a single partition, 
    // and write to the output
    // make sure the output location does not already exists 
    // (otherwise it will throw an error)
    r1.javaRDD().repartition(1).saveAsTextFile(output+"/q1/");

    /* Problem 2 */
    JavaRDD<Row> r2 = Q2(spark, dataFile);
    r2.repartition(1).saveAsTextFile(output+"/q2/");

    /* Problem 3 */
    JavaPairRDD<Tuple2<String, Integer>, Integer> r3 = Q3(spark, dataFile);
    r3.repartition(1).saveAsTextFile(output+"/q3/");

    /* Problem 4 */
    Tuple2<String, Integer> r4 = Q4(spark, dataFile);
    spark.createDataset(Collections.singletonList(r4), Encoders.tuple(Encoders.STRING(), Encoders.INT())).javaRDD().saveAsTextFile(output+"/q4/");

    /* Problem 5 */
    JavaPairRDD<String, Double> r5 = Q5(spark, dataFile);
    r5.repartition(1).saveAsTextFile(output+"/q5/");
    // this saves the results to an output file in parquet format
    // (useful if you want to generate a test dataset on an even smaller dataset)
    // r.repartition(1).write().parquet(output);

    // shut down
    spark.stop();
  }

  // offsets into each Row from the input data read
  public static final int MONTH = 2;
  public static final int ORIGIN_CITY_NAME = 15;
  public static final int DEST_CITY_NAME = 24;
  public static final int DEP_DELAY = 32;
  public static final int CANCELLED = 47;

  public static Dataset<Row> warmup (SparkSession spark, String dataFile) {

    Dataset<Row> df = spark.read().parquet(dataFile);

    // create a temporary table based on the data that we read
    df.createOrReplaceTempView("flights");

    // run a SQL query
    Dataset<Row> r = spark.sql("SELECT * FROM flights LIMIT 10");

    // this prints out the results
    r.show();

    // this uses the RDD API to project a column from the read data 
    // and print out the results

    r.javaRDD()
     .map(t -> {
       return t.get(DEST_CITY_NAME);
     })
     .foreach(t -> System.out.println(t));

    return r;
  }

  public static Dataset<Row> Q1 (SparkSession spark, String dataFile) {

    Dataset<Row> df = spark.read().parquet(dataFile);
    // your code here   
    df.createOrReplaceTempView("flights");

    Dataset<Row> sqlDF = spark.sql("SELECT DISTINCT destcityname FROM flights WHERE origincityname = 'Seattle, WA' ");

    sqlDF.show();

    return sqlDF;
  }

  public static JavaRDD<Row> Q2 (SparkSession spark, String dataFile) {

    JavaRDD<Row> rdd = spark.read().parquet(dataFile).javaRDD();
    // your code here
    JavaRDD<Row> result = rdd.filter(t -> {return t.getString(ORIGIN_CITY_NAME).compareTo("Seattle, WA") == 0;
    }).map(t -> {return RowFactory.create(t.getString(DEST_CITY_NAME));}).distinct();
    return result;
  }

  //Find the number of non-cancelled flights per month that departs from each city, 
  //return the results in a RDD where the key is a pair (i.e., a Tuple2 object),
  //consisting of a String for the departing city name, and an Integer for the month. 
  //The value should be the number of non-cancelled flights. 
  //Save the EMR output as q3.txt and add it to your repo. (20 points) [Result Size: 4383 rows (281 rows on the small dataset), 17 mins on EMR]

  public static JavaPairRDD<Tuple2<String, Integer>, Integer> Q3 (SparkSession spark, String dataFile) {

    JavaRDD<Row> df = spark.read().parquet(dataFile).javaRDD();
    // your code here    
    JavaPairRDD<Tuple2<String, Integer>, Integer> result = df.filter(t -> {return t.getInt(CANCELLED) == 0;}).mapToPair(t -> {return new Tuple2<Tuple2<String, Integer>, Integer>(new Tuple2<String, Integer>(t.getString(ORIGIN_CITY_NAME), t.getInt(MONTH)), 1);}).reduceByKey((a, b) -> a + b);
    return result;
  }


  //Find the name of the city that is connected to the most number of other cities
  // within a single hop flight. Return the result as a pair that consists of a String
  // for the city name, and an Integer for the total number of flights 
  //to the other cities. Save the EMR output as q4.txt and add it to your repo. (25 points) [Result Size: 1 row, 19 mins on EMR]

  public static Tuple2<String, Integer> Q4 (SparkSession spark, String dataFile) {

    JavaRDD<Row> d = spark.read().parquet(dataFile).javaRDD();

    // your code here
    d = d.map(t -> {return RowFactory.create(t.getString(ORIGIN_CITY_NAME), t.getString(DEST_CITY_NAME));}).distinct();
    JavaPairRDD<String, Integer> tmp = d.mapToPair(t-> {return new Tuple2<String, Integer>(t.getString(1), 1);}).reduceByKey((a, b) -> a + b);
    Tuple2<String, Integer> result = tmp.max(new DummyComparator());
    return result;
  }


  //Compute the average delay from all departing flights for each city. 
  //Flights with null delay values (due to cancellation or otherwise) 
  //should not be counted. Return the results in a RDD where 
  //the key is a String for the city name, and the value is a Double for the average delay in minutes. Save the EMR output as q5.txt and add it to your repo. (25 points) [Result Size: 383 rows (281 rows on the small dataset), 17 mins on EMR]

  public static JavaPairRDD<String, Double> Q5 (SparkSession spark, String dataFile) {
    JavaRDD<Row> d = spark.read().parquet(dataFile).javaRDD();
    // your code here
    JavaPairRDD<String, Tuple2<Integer, Integer> > tmp = d.mapToPair(
            t -> {
                  if (t.isNullAt(DEP_DELAY))
                    return new Tuple2<String, Tuple2<Integer, Integer>>(t.getString(ORIGIN_CITY_NAME), new Tuple2<Integer, Integer>(0,0));
                  else
                    return new Tuple2<String, Tuple2<Integer, Integer>>(t.getString(ORIGIN_CITY_NAME), new Tuple2<Integer, Integer>(1, t.getInt(DEP_DELAY)));
            }).reduceByKey((a, b) -> {return new Tuple2<Integer, Integer>(a._1 + b._1, a._2 + b._2);});
    JavaRDD<Tuple2<String, Double>> avg_delay = tmp.map(t -> {return  new Tuple2<String, Double>(t._1, (t._2._2 * 1.0) / (t._2._1 * 1.0));});
    return avg_delay.mapToPair(t -> t);
  }

  /* We list all the fields in the input data file for your reference
  root
 |-- year: integer (nullable = true)   // index 0
 |-- quarter: integer (nullable = true)
 |-- month: integer (nullable = true)
 |-- dayofmonth: integer (nullable = true)
 |-- dayofweek: integer (nullable = true)
 |-- flightdate: string (nullable = true)
 |-- uniquecarrier: string (nullable = true)
 |-- airlineid: integer (nullable = true)
 |-- carrier: string (nullable = true)
 |-- tailnum: string (nullable = true)
 |-- flightnum: integer (nullable = true)
 |-- originairportid: integer (nullable = true)
 |-- originairportseqid: integer (nullable = true)
 |-- origincitymarketid: integer (nullable = true)
 |-- origin: string (nullable = true)   // airport short name
 |-- origincityname: string (nullable = true) // e.g., Seattle, WA
 |-- originstate: string (nullable = true)
 |-- originstatefips: integer (nullable = true)
 |-- originstatename: string (nullable = true)
 |-- originwac: integer (nullable = true)
 |-- destairportid: integer (nullable = true)
 |-- destairportseqid: integer (nullable = true)
 |-- destcitymarketid: integer (nullable = true)
 |-- dest: string (nullable = true)
 |-- destcityname: string (nullable = true)
 |-- deststate: string (nullable = true)
 |-- deststatefips: integer (nullable = true)
 |-- deststatename: string (nullable = true)
 |-- destwac: integer (nullable = true)
 |-- crsdeptime: integer (nullable = true)
 |-- deptime: integer (nullable = true)
 |-- depdelay: integer (nullable = true)
 |-- depdelayminutes: integer (nullable = true)
 |-- depdel15: integer (nullable = true)
 |-- departuredelaygroups: integer (nullable = true)
 |-- deptimeblk: integer (nullable = true)
 |-- taxiout: integer (nullable = true)
 |-- wheelsoff: integer (nullable = true)
 |-- wheelson: integer (nullable = true)
 |-- taxiin: integer (nullable = true)
 |-- crsarrtime: integer (nullable = true)
 |-- arrtime: integer (nullable = true)
 |-- arrdelay: integer (nullable = true)
 |-- arrdelayminutes: integer (nullable = true)
 |-- arrdel15: integer (nullable = true)
 |-- arrivaldelaygroups: integer (nullable = true)
 |-- arrtimeblk: string (nullable = true)
 |-- cancelled: integer (nullable = true)
 |-- cancellationcode: integer (nullable = true)
 |-- diverted: integer (nullable = true)
 |-- crselapsedtime: integer (nullable = true)
 |-- actualelapsedtime: integer (nullable = true)
 |-- airtime: integer (nullable = true)
 |-- flights: integer (nullable = true)
 |-- distance: integer (nullable = true)
 |-- distancegroup: integer (nullable = true)
 |-- carrierdelay: integer (nullable = true)
 |-- weatherdelay: integer (nullable = true)
 |-- nasdelay: integer (nullable = true)
 |-- securitydelay: integer (nullable = true)
 |-- lateaircraftdelay: integer (nullable = true)
 |-- firstdeptime: integer (nullable = true)
 |-- totaladdgtime: integer (nullable = true)
 |-- longestaddgtime: integer (nullable = true)
 |-- divairportlandings: integer (nullable = true)
 |-- divreacheddest: integer (nullable = true)
 |-- divactualelapsedtime: integer (nullable = true)
 |-- divarrdelay: integer (nullable = true)
 |-- divdistance: integer (nullable = true)
 |-- div1airport: integer (nullable = true)
 |-- div1airportid: integer (nullable = true)
 |-- div1airportseqid: integer (nullable = true)
 |-- div1wheelson: integer (nullable = true)
 |-- div1totalgtime: integer (nullable = true)
 |-- div1longestgtime: integer (nullable = true)
 |-- div1wheelsoff: integer (nullable = true)
 |-- div1tailnum: integer (nullable = true)
 |-- div2airport: integer (nullable = true)
 |-- div2airportid: integer (nullable = true)
 |-- div2airportseqid: integer (nullable = true)
 |-- div2wheelson: integer (nullable = true)
 |-- div2totalgtime: integer (nullable = true)
 |-- div2longestgtime: integer (nullable = true)
 |-- div2wheelsoff: integer (nullable = true)
 |-- div2tailnum: integer (nullable = true)
 |-- div3airport: integer (nullable = true)
 |-- div3airportid: integer (nullable = true)
 |-- div3airportseqid: integer (nullable = true)
 |-- div3wheelson: integer (nullable = true)
 |-- div3totalgtime: integer (nullable = true)
 |-- div3longestgtime: integer (nullable = true)
 |-- div3wheelsoff: integer (nullable = true)
 |-- div3tailnum: integer (nullable = true)
 |-- div4airport: integer (nullable = true)
 |-- div4airportid: integer (nullable = true)
 |-- div4airportseqid: integer (nullable = true)
 |-- div4wheelson: integer (nullable = true)
 |-- div4totalgtime: integer (nullable = true)
 |-- div4longestgtime: integer (nullable = true)
 |-- div4wheelsoff: integer (nullable = true)
 |-- div4tailnum: integer (nullable = true)
 |-- div5airport: integer (nullable = true)
 |-- div5airportid: integer (nullable = true)
 |-- div5airportseqid: integer (nullable = true)
 |-- div5wheelson: integer (nullable = true)
 |-- div5totalgtime: integer (nullable = true)
 |-- div5longestgtime: integer (nullable = true)
 |-- div5wheelsoff: integer (nullable = true)
 |-- div5tailnum: integer (nullable = true)
   */
}
