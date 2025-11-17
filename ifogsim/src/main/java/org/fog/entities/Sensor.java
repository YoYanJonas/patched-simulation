package org.fog.entities;

import java.util.ArrayList;

import org.cloudbus.cloudsim.UtilizationModelFull;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;
import org.fog.application.AppEdge;
import org.fog.application.AppLoop;
import org.fog.application.Application;
import org.fog.utils.*;
import org.fog.utils.distribution.Distribution;

public class Sensor extends SimEntity{
	
	private int gatewayDeviceId;
	private GeoLocation geoLocation;
	private long outputSize;
	private String appId;
	private int userId;
	private String tupleType;
	private String sensorName;
	private String destModuleName;
	private Distribution transmitDistribution;
	private int controllerId;
	private Application app;
	private double latency;

	private int transmissionStartDelay = Config.TRANSMISSION_START_DELAY;
	
	public Sensor(String name, int userId, String appId, int gatewayDeviceId, double latency, GeoLocation geoLocation, 
			Distribution transmitDistribution, int cpuLength, int nwLength, String tupleType, String destModuleName) {
		super(name);
		this.setAppId(appId);
		this.gatewayDeviceId = gatewayDeviceId;
		this.geoLocation = geoLocation;
		this.outputSize = 3;
		this.setTransmitDistribution(transmitDistribution);
		setUserId(userId);
		setDestModuleName(destModuleName);
		setTupleType(tupleType);
		setSensorName(sensorName);
		setLatency(latency);
	}
	
	public Sensor(String name, int userId, String appId, int gatewayDeviceId, double latency, GeoLocation geoLocation, 
			Distribution transmitDistribution, String tupleType) {
		super(name);
		this.setAppId(appId);
		this.gatewayDeviceId = gatewayDeviceId;
		this.geoLocation = geoLocation;
		this.outputSize = 3;
		this.setTransmitDistribution(transmitDistribution);
		setUserId(userId);
		setTupleType(tupleType);
		setSensorName(sensorName);
		setLatency(latency);
	}
	
	/**
	 * This constructor is called from the code that generates PhysicalTopology from JSON
	 * @param name
	 * @param tupleType
	 * @param string 
	 * @param userId
	 * @param appId
	 * @param transmitDistribution
	 */
	public Sensor(String name, String tupleType, int userId, String appId, Distribution transmitDistribution) {
		super(name);
		this.setAppId(appId);
		this.setTransmitDistribution(transmitDistribution);
		setTupleType(tupleType);
		setSensorName(tupleType);
		setUserId(userId);
	}
	
	public void transmit(){
		// [DEBUG] Log sensor transmission start
		double currentTime = org.cloudbus.cloudsim.core.CloudSim.clock();
		System.out.println(String.format(
				"[FLOW-SENSOR-TRANSMIT] Time: %.2f - Sensor %s (ID:%d) - Starting transmit() - AppId:%s, GatewayDeviceId:%d",
				currentTime, getName(), getId(), getAppId(), getGatewayDeviceId()));
		
		AppEdge _edge = null;
		if (getApp() == null) {
			System.err.println(String.format(
					"[FLOW-SENSOR-TRANSMIT] Time: %.2f - Sensor %s (ID:%d) - ERROR: Application is NULL, cannot transmit!",
					currentTime, getName(), getId()));
			return;
		}
		
		for(AppEdge edge : getApp().getEdges()){
			if(edge.getSource().equals(getTupleType()))
				_edge = edge;
		}
		
		if (_edge == null) {
			System.err.println(String.format(
					"[FLOW-SENSOR-TRANSMIT] Time: %.2f - Sensor %s (ID:%d) - ERROR: No AppEdge found for tupleType '%s'",
					currentTime, getName(), getId(), getTupleType()));
			return;
		}
		
		// Get CPU from options (if configured) or fall back to AppEdge value
		long cpuLength = getRandomCpuFromConfig();
		if (cpuLength == 0) {
			cpuLength = (long) _edge.getTupleCpuLength();  // Fallback to AppEdge
		}

		// Get Memory from options (if configured) or use AppEdge network length as fallback
		long memorySize = getRandomMemoryFromConfig();
		if (memorySize == 0) {
			memorySize = (long) _edge.getTupleNwLength();  // Fallback to current behavior (nwLength)
		}

		long nwLength = (long) _edge.getTupleNwLength();  // Keep network length separate
		
		// Create Tuple: (appId, cloudletId, direction, cloudletLength, pesNumber, cloudletFileSize, cloudletOutputSize, ...)
		Tuple tuple = new Tuple(getAppId(), FogUtils.generateTupleId(), Tuple.UP, 
			cpuLength,      // CloudletLength = CPU (MIPS)
			1,              // pesNumber = 1
			memorySize,     // CloudletFileSize = Memory (bytes) - NOW FROM RANDOM OPTIONS
			outputSize,     // CloudletOutputSize = output size (bytes) - keep from current behavior
			new UtilizationModelFull(), new UtilizationModelFull(), new UtilizationModelFull());
		tuple.setUserId(getUserId());
		tuple.setTupleType(getTupleType());
		
		tuple.setDestModuleName(_edge.getDestination());
		tuple.setSrcModuleName(getSensorName());
		Logger.debug(getName(), "Sending tuple with tupleId = "+tuple.getCloudletId());

		tuple.setDestinationDeviceId(getGatewayDeviceId());

		int actualTupleId = updateTimings(getSensorName(), tuple.getDestModuleName());
		tuple.setActualTupleId(actualTupleId);
		
		// [DEBUG] Log before sending
		System.out.println(String.format(
				"[FLOW-SENSOR-TRANSMIT] Time: %.2f - Sensor %s (ID:%d) - Sending tuple %d to GatewayDevice %d (DestModule:%s, CPU:%d, Mem:%d, NW:%d, Latency:%.2f)",
				currentTime, getName(), getId(), tuple.getCloudletId(), gatewayDeviceId, 
				tuple.getDestModuleName(), cpuLength, memorySize, nwLength, getLatency()));
		
		send(gatewayDeviceId, getLatency(), FogEvents.TUPLE_ARRIVAL,tuple);
		
		// [DEBUG] Log after sending
		System.out.println(String.format(
				"[FLOW-SENSOR-TRANSMIT] Time: %.2f - Sensor %s (ID:%d) - Tuple %d SENT successfully to device %d",
				currentTime, getName(), getId(), tuple.getCloudletId(), gatewayDeviceId));
	}

	/**
	 * Get random CPU value from configuration options
	 * @return Random CPU value from options, or 0 if not configured
	 */
	private long getRandomCpuFromConfig() {
		java.util.List<Long> cpuOptions = org.patch.config.EnhancedConfigurationLoader.getSensorConfigList("sensors.parameters.cpu.options");
		if (cpuOptions != null && !cpuOptions.isEmpty()) {
			java.util.Random random = new java.util.Random();
			return cpuOptions.get(random.nextInt(cpuOptions.size()));
		}
		return 0; // Indicate not configured
	}

	/**
	 * Get random Memory value from configuration options
	 * @return Random Memory value from options, or 0 if not configured
	 */
	private long getRandomMemoryFromConfig() {
		java.util.List<Long> memoryOptions = org.patch.config.EnhancedConfigurationLoader.getSensorConfigList("sensors.parameters.memory.options");
		if (memoryOptions != null && !memoryOptions.isEmpty()) {
			java.util.Random random = new java.util.Random();
			return memoryOptions.get(random.nextInt(memoryOptions.size()));
		}
		return 0; // Indicate not configured
	}
	
	protected int updateTimings(String src, String dest){
		Application application = getApp();
		for(AppLoop loop : application.getLoops()){
			if(loop.hasEdge(src, dest)){
				
				int tupleId = TimeKeeper.getInstance().getUniqueId();
				if(!TimeKeeper.getInstance().getLoopIdToTupleIds().containsKey(loop.getLoopId()))
					TimeKeeper.getInstance().getLoopIdToTupleIds().put(loop.getLoopId(), new ArrayList<Integer>());
				TimeKeeper.getInstance().getLoopIdToTupleIds().get(loop.getLoopId()).add(tupleId);
				TimeKeeper.getInstance().getEmitTimes().put(tupleId, CloudSim.clock());
				return tupleId;
			}
		}
		return -1;
	}
	
	@Override
	public void startEntity() {
		// Send SENSOR_JOINED event to gateway device
		if (geoLocation != null) {
			send(gatewayDeviceId, CloudSim.getMinTimeBetweenEvents(), FogEvents.SENSOR_JOINED, geoLocation);
			System.out.println(String.format(
					"[FLOW-SENSOR-START] Time: %.2f - Sensor %s (ID:%d) started - Gateway:%d, AppId:%s, App:%s",
					CloudSim.clock(), getName(), getId(), gatewayDeviceId, getAppId(),
					getApp() != null ? "SET" : "NULL"));
		} else {
			System.err.println(String.format(
					"[FLOW-SENSOR-START] Time: %.2f - Sensor %s (ID:%d) - ERROR: GeoLocation is NULL!",
					CloudSim.clock(), getName(), getId()));
		}
		
		// Schedule first EMIT_TUPLE event, but only if we have an application reference
		// If app is null, we'll schedule it later when app is set
		if (getApp() != null) {
			double nextTransmitTime = getTransmitDistribution().getNextValue() + transmissionStartDelay;
			send(getId(), nextTransmitTime, FogEvents.EMIT_TUPLE);
			System.out.println(String.format(
					"[FLOW-SENSOR-START] Time: %.2f - Sensor %s (ID:%d) scheduled first EMIT_TUPLE at time %.2f",
					CloudSim.clock(), getName(), getId(), CloudSim.clock() + nextTransmitTime));
		} else {
			System.out.println(String.format(
					"[FLOW-SENSOR-START] Time: %.2f - Sensor %s (ID:%d) - Application not yet set, will schedule EMIT_TUPLE when app is available",
					CloudSim.clock(), getName(), getId()));
		}
	}

	@Override
	public void processEvent(SimEvent ev) {
		switch(ev.getTag()){
		case FogEvents.TUPLE_ACK:
			//transmit(transmitDistribution.getNextValue());
			break;
		case FogEvents.EMIT_TUPLE:
			double currentTime = CloudSim.clock();
			double simulationTime = org.fog.utils.Config.SIMULATION_TIME;
			double maxSimulationTime = org.fog.utils.Config.MAX_SIMULATION_TIME;
			
			// Stop generating NEW tuples once we've reached SIMULATION_TIME
			// MAX_SIMULATION_TIME is a hard cap (should not reach here if working correctly)
			if (currentTime >= simulationTime) {
				System.out.println(String.format(
						"[FLOW-SENSOR-STOP] Time: %.2f - Sensor %s (ID:%d) stopping tuple generation - Current time >= SIMULATION_TIME %.2f",
						currentTime, getName(), getId(), simulationTime));
				return; // Don't transmit or schedule next
			}
			
			// Safety check: Also stop if we somehow exceeded MAX_SIMULATION_TIME
			if (currentTime >= maxSimulationTime) {
				System.out.println(String.format(
						"[FLOW-SENSOR-STOP] Time: %.2f - Sensor %s (ID:%d) HARD STOP - Current time >= MAX_SIMULATION_TIME %.2f",
						currentTime, getName(), getId(), maxSimulationTime));
				return;
			}
			
			// Transmit current tuple
			transmit();
			
			// Schedule next transmission only if it would occur before SIMULATION_TIME
			if (getTransmitDistribution() != null) {
				double nextTransmitTime = getTransmitDistribution().getNextValue();
				double nextEventTime = currentTime + nextTransmitTime;
				
				if (nextEventTime < simulationTime) {
					send(getId(), nextTransmitTime, FogEvents.EMIT_TUPLE);
				} else {
					System.out.println(String.format(
							"[FLOW-SENSOR-STOP] Time: %.2f - Sensor %s (ID:%d) stopping tuple scheduling - Next event time %.2f >= SIMULATION_TIME %.2f",
							currentTime, getName(), getId(), nextEventTime, simulationTime));
				}
			}
			break;
		}
			
	}

	@Override
	public void shutdownEntity() {
		
	}

	public int getGatewayDeviceId() {
		return gatewayDeviceId;
	}

	public void setGatewayDeviceId(int gatewayDeviceId) {
		this.gatewayDeviceId = gatewayDeviceId;
	}

	public GeoLocation getGeoLocation() {
		return geoLocation;
	}

	public void setGeoLocation(GeoLocation geoLocation) {
		this.geoLocation = geoLocation;
	}

	public int getUserId() {
		return userId;
	}

	public void setUserId(int userId) {
		this.userId = userId;
	}

	public String getTupleType() {
		return tupleType;
	}

	public void setTupleType(String tupleType) {
		this.tupleType = tupleType;
	}

	public String getSensorName() {
		return sensorName;
	}

	public void setSensorName(String sensorName) {
		this.sensorName = sensorName;
	}

	public String getAppId() {
		return appId;
	}

	public void setAppId(String appId) {
		this.appId = appId;
	}

	public String getDestModuleName() {
		return destModuleName;
	}

	public void setDestModuleName(String destModuleName) {
		this.destModuleName = destModuleName;
	}

	public Distribution getTransmitDistribution() {
		return transmitDistribution;
	}

	public void setTransmitDistribution(Distribution transmitDistribution) {
		this.transmitDistribution = transmitDistribution;
	}

	public int getControllerId() {
		return controllerId;
	}

	public void setControllerId(int controllerId) {
		this.controllerId = controllerId;
	}

	public Application getApp() {
		return app;
	}

	public void setApp(Application app) {
		this.app = app;
		double currentTime = CloudSim.clock();
		
		// [DEBUG] Log when app is set
		System.out.println(String.format(
				"[FLOW-SENSOR-APP-SET] Time: %.2f - Sensor %s (ID:%d) - Application reference SET (AppId:%s)",
				currentTime, getName(), getId(), getAppId()));
		
		// If sensor has started but hasn't scheduled first EMIT_TUPLE yet (because app was null),
		// schedule it now
		if (app != null && getTransmitDistribution() != null) {
			double nextTransmitTime = getTransmitDistribution().getNextValue() + transmissionStartDelay;
			send(getId(), nextTransmitTime, FogEvents.EMIT_TUPLE);
			System.out.println(String.format(
					"[FLOW-SENSOR-APP-SET] Time: %.2f - Sensor %s (ID:%d) scheduled first EMIT_TUPLE at time %.2f (after app was set)",
					currentTime, getName(), getId(), currentTime + nextTransmitTime));
		}
	}

	public Double getLatency() {
		return latency;
	}

	public void setLatency(Double latency) {
		this.latency = latency;
	}

	protected long getOutputSize(){return this.outputSize;}

	public void setTransmissionStartDelay(int transmissionStartDelay) {
		this.transmissionStartDelay = transmissionStartDelay;
	}

	public int getTransmissionStartDelay() {
		return transmissionStartDelay;
	}

}
