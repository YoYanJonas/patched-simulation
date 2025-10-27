package org.patch.examples;

import org.patch.utils.DeviceFactory;
import org.patch.utils.SchedulerConfig;
import org.patch.devices.RLFogDevice;
import org.patch.devices.RLCloudDevice;
import org.cloudbus.cloudsim.power.models.PowerModelLinear;

/**
 * Example demonstrating how to use the updated DeviceFactory
 * with proper scheduler host/port assignment
 */
public class DeviceFactoryExample {

    public static void main(String[] args) {
        System.out.println("=== DeviceFactory Example ===");

        // Example 1: Create fog devices with ID-based scheduler assignment
        System.out.println("\n1. Creating fog devices with scheduler assignment:");

        for (int i = 1; i <= 12; i++) {
            try {
                RLFogDevice fogDevice = DeviceFactory.createRLFogDeviceWithId(
                        "fog-node-" + i, // name
                        1000, // mips
                        1024, // ram (MB)
                        100.0, // uplink bandwidth
                        100.0, // downlink bandwidth
                        1.0, // rate per mips
                        100.0, // busy power
                        10.0, // idle power
                        i // fog node ID (1-12)
                );

                // Get the assigned scheduler configuration
                Object[] schedulerConfig = SchedulerConfig.getSchedulerConfigForFogNode(i);
                String schedulerHost = (String) schedulerConfig[0];
                int schedulerPort = (Integer) schedulerConfig[1];

                System.out.println("  Fog Node " + i + " -> Scheduler: " + schedulerHost + ":" + schedulerPort);

            } catch (Exception e) {
                System.err.println("  Failed to create fog node " + i + ": " + e.getMessage());
            }
        }

        // Example 2: Create cloud devices with allocation service
        System.out.println("\n2. Creating cloud devices with allocation service:");

        try {
            RLCloudDevice cloudDevice1 = DeviceFactory.createRLCloudDevice(
                    "cloud-node-1", // name
                    2000, // mips
                    2048, // ram (MB)
                    1000.0, // uplink bandwidth
                    1000.0, // downlink bandwidth
                    1.0, // rate per mips
                    200.0, // busy power
                    20.0 // idle power
            );

            // Get the allocation service configuration
            Object[] allocationConfig = SchedulerConfig.getAllocationConfig();
            String allocationHost = (String) allocationConfig[0];
            int allocationPort = (Integer) allocationConfig[1];

            System.out.println("  Cloud Node 1 -> Allocation Service: " + allocationHost + ":" + allocationPort);

        } catch (Exception e) {
            System.err.println("  Failed to create cloud node: " + e.getMessage());
        }

        // Example 3: Create devices with custom scheduler configuration
        System.out.println("\n3. Creating devices with custom scheduler configuration:");

        try {
            RLFogDevice customFogDevice = DeviceFactory.createRLFogDevice(
                    "custom-fog-node", // name
                    1000, // mips
                    1024, // ram (MB)
                    100.0, // uplink bandwidth
                    100.0, // downlink bandwidth
                    1.0, // rate per mips
                    100.0, // busy power
                    10.0, // idle power
                    "custom-scheduler-host", // custom scheduler host
                    50052 // custom scheduler port
            );

            System.out.println("  Custom Fog Node -> Custom Scheduler: custom-scheduler-host:50052");

        } catch (Exception e) {
            System.err.println("  Failed to create custom fog node: " + e.getMessage());
        }

        // Example 4: Show scheduler assignment mapping
        System.out.println("\n4. Scheduler Assignment Mapping:");
        System.out.println("  Fog Nodes 1-3   -> grpc-task-scheduler-1:40040");
        System.out.println("  Fog Nodes 4-6   -> grpc-task-scheduler-2:40040");
        System.out.println("  Fog Nodes 7-9   -> grpc-task-scheduler-3:40040");
        System.out.println("  Fog Nodes 10-12 -> grpc-task-scheduler-4:40040");
        System.out.println("  Cloud Nodes     -> go-grpc-server:50051");

        // Example 5: Environment variable configuration
        System.out.println("\n5. Environment Variable Configuration:");
        System.out.println("  Set these environment variables to override defaults:");
        System.out.println("  IFOGSIM_SCHEDULER_HOST_1=grpc-task-scheduler-1");
        System.out.println("  IFOGSIM_SCHEDULER_PORT_1=40040");
        System.out.println("  IFOGSIM_SCHEDULER_HOST_2=grpc-task-scheduler-2");
        System.out.println("  IFOGSIM_SCHEDULER_PORT_2=40040");
        System.out.println("  IFOGSIM_ALLOCATION_HOST=go-grpc-server");
        System.out.println("  IFOGSIM_ALLOCATION_PORT=50051");

        System.out.println("\n=== Example Complete ===");
    }
}
