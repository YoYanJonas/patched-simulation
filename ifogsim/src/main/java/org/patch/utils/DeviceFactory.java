package org.patch.utils;

import org.fog.entities.FogDevice;
import org.patch.devices.RLFogDevice;
import org.patch.devices.RLCloudDevice;
import org.cloudbus.cloudsim.power.models.PowerModel;
import org.cloudbus.cloudsim.power.models.PowerModelLinear;

/**
 * Factory for creating extended device instances
 */
public class DeviceFactory {

    /**
     * Create a standard FogDevice
     */
    public static FogDevice createFogDevice(String name, long mips, int ram, double upBw, double downBw,
            double ratePerMips, double busyPower, double idlePower) {
        try {
            return new FogDevice(name, mips, ram, upBw, downBw, ratePerMips,
                    new PowerModelLinear(busyPower, idlePower));
        } catch (Exception e) {
            throw new RuntimeException("Failed to create FogDevice", e);
        }
    }

    /**
     * Create an RL-enabled FogDevice with default scheduler configuration
     */
    public static RLFogDevice createRLFogDevice(String name, long mips, int ram, double upBw, double downBw,
            double ratePerMips, double busyPower, double idlePower) {
        try {
            // Use default scheduler configuration
            String schedulerHost = SchedulerConfig.getDefaultSchedulerHost();
            int schedulerPort = SchedulerConfig.getDefaultSchedulerPort();
            return new RLFogDevice(name, mips, ram, upBw, downBw, ratePerMips, busyPower, idlePower,
                    schedulerHost, schedulerPort);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create RLFogDevice", e);
        }
    }

    /**
     * Create an RL-enabled FogDevice with specific RL server configuration
     */
    public static RLFogDevice createRLFogDevice(String name, long mips, int ram, double upBw, double downBw,
            double ratePerMips, double busyPower, double idlePower,
            String rlServerHost, int rlServerPort) {
        try {
            return new RLFogDevice(name, mips, ram, upBw, downBw, ratePerMips, busyPower, idlePower,
                    rlServerHost, rlServerPort);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create RLFogDevice with server config", e);
        }
    }

    /**
     * Convert a standard FogDevice to an RL-enabled one
     * Note: This creates a new device with the same parameters
     */
    public static RLFogDevice convertToRLFogDevice(FogDevice device) {
        if (device instanceof RLFogDevice) {
            return (RLFogDevice) device;
        }

        try {
            // Extract power model values - this depends on the actual implementation
            double busyPower = 0.0;
            double idlePower = 0.0;

            // If the device has a PowerModelLinear
            PowerModel powerModel = device.getHost().getPowerModel();
            if (powerModel instanceof PowerModelLinear) {
                PowerModelLinear linearModel = (PowerModelLinear) powerModel;
                // These methods might need to be adjusted based on actual PowerModelLinear
                // implementation
                // or you might need to use reflection if they're not publicly accessible
                busyPower = linearModel.getPower(1.0); // Power at max utilization
                idlePower = linearModel.getPower(0.0); // Power at idle
            }

            // Create a new RLFogDevice with the same parameters
            // Use default scheduler configuration for converted devices
            String schedulerHost = SchedulerConfig.getDefaultSchedulerHost();
            int schedulerPort = SchedulerConfig.getDefaultSchedulerPort();

            RLFogDevice rlDevice = new RLFogDevice(
                    device.getName(),
                    device.getHost().getTotalMips(),
                    (int) device.getHost().getRam(),
                    device.getUplinkBandwidth(),
                    device.getDownlinkBandwidth(),
                    device.getRatePerMips(),
                    busyPower,
                    idlePower,
                    schedulerHost,
                    schedulerPort);

            // Copy other relevant properties
            rlDevice.setParentId(device.getParentId());
            rlDevice.setLevel(device.getLevel());

            // You may need to copy more properties depending on your requirements

            return rlDevice;
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert to RLFogDevice", e);
        }
    }

    /**
     * Create an RL-enabled FogDevice with scheduler assignment based on fog node ID
     * 
     * @param name        Device name
     * @param mips        MIPS capacity
     * @param ram         RAM capacity
     * @param upBw        Uplink bandwidth
     * @param downBw      Downlink bandwidth
     * @param ratePerMips Rate per MIPS
     * @param busyPower   Busy power consumption
     * @param idlePower   Idle power consumption
     * @param fogNodeId   Fog node ID (1-12) for scheduler assignment
     * @return RL-enabled FogDevice
     */
    public static RLFogDevice createRLFogDeviceWithId(String name, long mips, int ram, double upBw, double downBw,
            double ratePerMips, double busyPower, double idlePower, int fogNodeId) {
        try {
            // Get scheduler configuration based on fog node ID
            Object[] config = SchedulerConfig.getSchedulerConfigForFogNode(fogNodeId);
            String schedulerHost = (String) config[0];
            int schedulerPort = (Integer) config[1];

            return new RLFogDevice(name, mips, ram, upBw, downBw, ratePerMips, busyPower, idlePower,
                    schedulerHost, schedulerPort);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create RLFogDevice with ID " + fogNodeId, e);
        }
    }

    /**
     * Create an RL-enabled CloudDevice with allocation service configuration
     * 
     * @param name        Device name
     * @param mips        MIPS capacity
     * @param ram         RAM capacity
     * @param upBw        Uplink bandwidth
     * @param downBw      Downlink bandwidth
     * @param ratePerMips Rate per MIPS
     * @param powerModel  Power model
     * @return RL-enabled CloudDevice
     */
    public static RLCloudDevice createRLCloudDevice(String name, long mips, int ram, double upBw, double downBw,
            double ratePerMips, PowerModel powerModel) {
        try {
            // Get allocation service configuration
            Object[] config = SchedulerConfig.getAllocationConfig();
            String allocationHost = (String) config[0];
            int allocationPort = (Integer) config[1];

            return new RLCloudDevice(name, mips, ram, upBw, downBw, ratePerMips, powerModel,
                    allocationHost, allocationPort);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create RLCloudDevice", e);
        }
    }

    /**
     * Create an RL-enabled CloudDevice with busy/idle power
     * 
     * @param name        Device name
     * @param mips        MIPS capacity
     * @param ram         RAM capacity
     * @param upBw        Uplink bandwidth
     * @param downBw      Downlink bandwidth
     * @param ratePerMips Rate per MIPS
     * @param busyPower   Busy power consumption
     * @param idlePower   Idle power consumption
     * @return RL-enabled CloudDevice
     */
    public static RLCloudDevice createRLCloudDevice(String name, long mips, int ram, double upBw, double downBw,
            double ratePerMips, double busyPower, double idlePower) {
        try {
            // Get allocation service configuration
            Object[] config = SchedulerConfig.getAllocationConfig();
            String allocationHost = (String) config[0];
            int allocationPort = (Integer) config[1];

            return new RLCloudDevice(name, mips, ram, upBw, downBw, ratePerMips, busyPower, idlePower,
                    allocationHost, allocationPort);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create RLCloudDevice", e);
        }
    }
}