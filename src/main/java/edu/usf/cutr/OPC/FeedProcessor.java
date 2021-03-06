/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package edu.usf.cutr.OPC;

import edu.usf.cutr.OPC.gtfs.GtfsStatisticsService;
import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.TimeZone;
import java.util.logging.Logger;
import java.util.zip.ZipException;
import org.onebusaway.csv_entities.exceptions.CsvEntityIOException;
import org.onebusaway.csv_entities.exceptions.MissingRequiredFieldException;
import org.onebusaway.gtfs.impl.GtfsRelationalDaoImpl;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.gtfs.model.StopTime;
import org.onebusaway.gtfs.serialization.GtfsReader;

//import org.onebusaway.gtfs.impl.GtfsRelationalDaoImpl;
//import org.onebusaway.gtfs.serialization.GtfsReader;

public class FeedProcessor {
        public static final int MISSING_VALUE = -999;
	private File feed;
	private GtfsRelationalDaoImpl dao;
        private FeedValidationResult output;
        private final int numRecords;
        private final String arrivalOrDeparture;
	private static Logger _log = Logger.getLogger(FeedProcessor.class.getName());
	
	/**
	 * Create a feed processor for the given feed
	 * @param feed
         * @param numRecords
	 */
	public FeedProcessor (File feed, String arrivalOrDeparture, int numRecords) {
		this.feed = feed;
                this.numRecords = numRecords;
                this.arrivalOrDeparture = arrivalOrDeparture;
                this.output = new FeedValidationResult();
	}
	
	/**
	 * Load the feed into memory for processing.
	 * @throws IOException 
        * @throws java.lang.ClassNotFoundException 
        * @throws java.sql.SQLException 
	 */
	public void load () throws IOException, ClassNotFoundException, SQLException {
		_log.fine("Loading GTFS");
		
		// check if the file is accessible
		if (!feed.exists() || !feed.canRead())
			throw new IOException("File does not exist or not readable");
		
		output.feedFileName = feed.getName();
		
		// note: we have two references because a GtfsDao is not mutable and we can't load to it,
		// but a GtfsDaoImpl is.
		dao = new GtfsRelationalDaoImpl();
		GtfsReader reader = new GtfsReader();
		reader.setEntityStore(dao);
                // Exceptions here mean a problem with the file 
		try {
			reader.setInputLocation(feed);
			reader.run();
			output.loadStatus = LoadStatus.SUCCESS;
		}
		catch (ZipException e) {
			output.loadStatus = LoadStatus.INVALID_ZIP_FILE;
			output.loadFailureReason = "Invalid ZIP file, not a ZIP file, or file corrupted";
		}
		catch (CsvEntityIOException e) {
			Throwable cause = e.getCause();
			if (cause instanceof MissingRequiredFieldException) {
				output.loadStatus = LoadStatus.MISSING_REQUIRED_FIELD;
				output.loadFailureReason = cause.getMessage();
			}
			else if (cause instanceof IndexOutOfBoundsException) {
			    output.loadStatus = LoadStatus.INCORRECT_FIELD_COUNT_IMPROPER_QUOTING;
			    output.loadFailureReason = e.getMessage() + " (perhaps improper quoting)";
			}
			    
			else {
				output.loadStatus = LoadStatus.OTHER_FAILURE;
				output.loadFailureReason = "Unknown failure";
			}
		}
		catch (IOException e) {
			output.loadStatus = LoadStatus.OTHER_FAILURE;
		}
                System.err.println("\nLoadStatus of GTFS Feed: " + output.loadStatus);
                if(output.loadFailureReason != null) System.err.println("loadFailureReason of GTFS Feed: "+ output.loadFailureReason);
                
                GtfsStatisticsService stats = new GtfsStatisticsService(dao);
                
                Collection<StopTime> stopTimes = dao.getAllStopTimes();
                List<Float> minDistList = new ArrayList<Float>();
                List<String> closestStopIdList = new ArrayList<String>();
                List<Integer> oidList = new ArrayList<Integer>();
                List<Long> sched_deviation = new ArrayList<Long>();
                List<Integer> timepointList = new ArrayList<Integer>();
                
                Date calSvcStart = stats.getCalendarServiceRangeStart();
		Date calSvcEnd = stats.getCalendarServiceRangeEnd();
                
                SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");
                
                Timestamp startTimestamp = null;
                Timestamp endTimestamp = null;
                
                if(calSvcStart != null)
                    startTimestamp = new java.sql.Timestamp(calSvcStart.getTime());
                if(calSvcEnd != null)
                    endTimestamp = new java.sql.Timestamp(calSvcEnd.getTime());
                
                String fileName = "/info.txt";
                DatabaseConnectionInfo dbInfo = new DatabaseConnectionInfo(fileName);
                String database = dbInfo.getDatabase();
                Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
                Properties properties = new Properties();
                
                String URL = "jdbc:sqlserver://" + dbInfo.getServer();
                properties.setProperty("user", dbInfo.getUsername());
                properties.setProperty("password", dbInfo.getPassword());
                properties.setProperty("database", dbInfo.getDatabase());
                Connection conn = null;
                try {
                    conn = DriverManager.getConnection(URL, properties);
                } catch (SQLException ex) {
                    System.err.println("\nERROR: Database access error. Ensure the details provided in 'info.txt' file are correct.\n");
                    return;
                }
                
                String dbTable = "[" +database + "].[dbo].[vehicle_positions]";
                System.out.println("\nConnected to Database: " + database);
                String select;
                if(numRecords > 0)
                    select = "SELECT TOP("+numRecords+") ";
                else select = "SELECT ";
		String sql = select+"[oid], [timestamp], [trip_id], [position_latitude], [position_longitude]\n" +
                                "  FROM [" +database + "].[dbo].[vehicle_positions]" +
                                "  WHERE [timestamp] >= ? AND [timestamp] <= ?" +
                                "  ORDER BY [oid] DESC";
                
                PreparedStatement ps = conn.prepareStatement(sql);
                ps.setTimestamp(1, startTimestamp);
                ps.setTimestamp(2, endTimestamp);
                System.out.println("\nGTFS Data Valid Start Date: " + startTimestamp);
                System.out.println("\nGTFS Data Valid End Date: " + endTimestamp);
		ResultSet rs = ps.executeQuery();
                
                sql = "UPDATE [" +database + "].[dbo].[vehicle_positions]\n" +
                        "SET [closest_stop_id] = ?, [distance_to_stop] = ?, [schedule_deviation] = ?, [timepoint] = ? \n" +
                        "WHERE [oid] = ?";
                ps = conn.prepareStatement(sql);
                long rt_arrivaltime;
                String timezone = dao.getAllAgencies().iterator().next().getTimezone(); //if there are more than one agencies, they still have same timezone.
		while (rs.next()) {
                    String trip_id_rt = rs.getString("trip_id"); //gtfs-rt trip id
                    Double latVal = Double.parseDouble(Float.toString(rs.getFloat("position_latitude")));
                    Double lonVal = Double.parseDouble(Float.toString(rs.getFloat("position_longitude")));
                    Timestamp timestampUT = rs.getTimestamp("timestamp");
                    
                    Date timestampEST = new Date(timestampUT.getTime() + TimeZone.getTimeZone(timezone).getRawOffset());
                    Integer oid = rs.getInt("oid");
                    
                    String formatted = timeFormat.format(timestampEST.getTime());
                    rt_arrivaltime = getTimeInSeconds(formatted);
                    
                    int counter = 0;
                    Float dist;
                    Float minDist = null;
                    String closeStop = null;
                    long arrival_time = 0;
                    int timepoint = MISSING_VALUE;                    
                    for (StopTime stopTime : stopTimes) {
                        AgencyAndId id = stopTime.getTrip().getId();
                        String trip_id = id.getId(); //gtfs trip id
                        boolean timepointSet = stopTime.isTimepointSet();
                        
                        if(trip_id.equals(trip_id_rt))
                        {
                            Double lat = stopTime.getStop().getLat();
                            Double lon = stopTime.getStop().getLon();
                            
                            id = stopTime.getStop().getId();
                            String stop_id = id.getId();
                            
                            if(counter == 0) {
                                minDist = distbetweenPoints(lat, lon, latVal, lonVal);
                                closeStop = stop_id;
                                if(arrivalOrDeparture.equals("arrival_time"))
                                    arrival_time = stopTime.getArrivalTime();
                                else arrival_time = stopTime.getDepartureTime();
                                if(timepointSet)
                                    timepoint = stopTime.getTimepoint();
                                counter = 1;
                            }
                            else {
                                dist = distbetweenPoints(lat, lon, latVal, lonVal);
                                if(minDist > dist) {
                                    minDist = dist;
                                    closeStop = stop_id;
                                    if(arrivalOrDeparture.equals("arrival_time"))
                                        arrival_time = stopTime.getArrivalTime();
                                    else arrival_time = stopTime.getDepartureTime();
                                    if(timepointSet)
                                        timepoint = stopTime.getTimepoint();
                                }
                            }
                        }
                    }
                    if(counter == 1) { // check whether GTFS data contains trip_id_rt, if contains add minDist, closeStop, etc values to respective lists
                        minDistList.add(minDist);
                        closestStopIdList.add(closeStop);
                        oidList.add(oid);
                        if(rt_arrivaltime >= 0 && rt_arrivaltime <= 10800) //if rt_arrival_time is between midnight and 3am, treat as same day of service ;
                            rt_arrivaltime = rt_arrivaltime + 86400; // add 1 day; for eg. if it's 1:00:00am, make it 25:00:00
                        sched_deviation.add((rt_arrivaltime - arrival_time)*1000); //in milliseconds
                        if(timepoint == MISSING_VALUE)
                            timepointList.add(null);
                        else timepointList.add(timepoint);
                    }
                     
                    if(minDistList.size() == 10000) { //executes batch updates of 10,000 rows each time
                        for(int i = minDistList.size() - 1; i >= 0 ; i--) {
                            ps.setString(1, closestStopIdList.get(i));
                            ps.setFloat(2, minDistList.get(i));
                            ps.setLong(3, sched_deviation.get(i));
                            ps.setInt(4, timepointList.get(i));
                            ps.setInt(5, oidList.get(i));                    
                            ps.addBatch();
                        }
                        int update[] = ps.executeBatch();
                        if(update.length != minDistList.size()) {
                            System.err.println("\nUPDATE ERROR");
                            return;
                        }
                        closestStopIdList.clear();
                        minDistList.clear();
                        sched_deviation.clear();
                        timepointList.clear();
                        oidList.clear();
                    }
                }
                
                for(int i = minDistList.size() - 1; i >= 0 ; i--) {
                    ps.setString(1, closestStopIdList.get(i));
                    ps.setFloat(2, minDistList.get(i));
                    ps.setLong(3, sched_deviation.get(i));
                    ps.setInt(4, timepointList.get(i));
                    ps.setInt(5, oidList.get(i));                  
                    ps.addBatch();
                }
                int update[] = ps.executeBatch();
                if(update.length != minDistList.size()) {
                    System.err.println("\nUPDATE ERROR");
                    return;
                }
                conn.commit();
                    
                ClosestToStop cts = new ClosestToStop(dbTable, numRecords);
                ps = conn.prepareStatement(cts.updateClosestToStopField());
                ps.setTimestamp(1, startTimestamp);
                ps.setTimestamp(2, endTimestamp);
                ps.executeUpdate();
                conn.commit();
                conn.close();
                System.out.println("Finished updating table");                
	}
        
        public Float distbetweenPoints(Double lat1, Double lng1, Double lat2, Double lng2) {
                Double earthRadius = new Double(6371000); //meters
                Double dLat = Math.toRadians(lat2-lat1);
                Double dLng = Math.toRadians(lng2-lng1);
                Double a = Math.sin(dLat/2) * Math.sin(dLat/2) +
                           Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                           Math.sin(dLng/2) * Math.sin(dLng/2);
                Double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
                Float dist;
                    dist = new Float(earthRadius * c);

                return dist; //in meters
        }
        public Long getTimeInSeconds(String time) { //time is in HH:mm:ss format
            String[] timeSplit = time.split(":");
            return Long.parseLong(timeSplit[0])*3600
                    + Long.parseLong(timeSplit[1])*60
                    + Long.parseLong(timeSplit[2]);
        }
}
