/*
 *  ========================================================================
 *  DISSECT-CF Examples
 *  ========================================================================
 *  
 *  This file is part of DISSECT-CF Examples.
 *  
 *  DISSECT-CF Examples is free software: you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License as published
 *  by the Free Software Foundation, either version 3 of the License, or (at
 *  your option) any later version.
 *  
 *  DISSECT-CF Examples is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of 
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along
 *  with DISSECT-CF Examples.  If not, see <http://www.gnu.org/licenses/>.
 *  
 *  (C) Copyright 2019, Gabor Kecskemeti (g.kecskemeti@ljmu.ac.uk)
 */
package uk.ac.ljmu.fet.cs.cloud.examples.autoscaler;

import java.security.InvalidParameterException;

import java.util.HashMap;

import hu.mta.sztaki.lpds.cloud.simulator.Timed;
import hu.mta.sztaki.lpds.cloud.simulator.energy.specialized.IaaSEnergyMeter;
import hu.mta.sztaki.lpds.cloud.simulator.examples.jobhistoryprocessor.DCFJob;
import hu.mta.sztaki.lpds.cloud.simulator.examples.util.DCCreation;
import hu.mta.sztaki.lpds.cloud.simulator.helpers.trace.FileBasedTraceProducerFactory;
import hu.mta.sztaki.lpds.cloud.simulator.iaas.IaaSService;
import hu.mta.sztaki.lpds.cloud.simulator.iaas.PhysicalMachine;
import hu.mta.sztaki.lpds.cloud.simulator.iaas.pmscheduling.SchedulingDependentMachines;
import hu.mta.sztaki.lpds.cloud.simulator.iaas.vmscheduling.FirstFitScheduler;

/**
 * Simple driver of a job trace's simulation with an auto scaling and clustering
 * mechanism.
 * 
 * Notes that This example uses several simplifications to allow easy
 * recognition of the auto scaling related mechanisms. These simplifications
 * include:
 * <ul>
 * <li>VM size is constant and derived from the _name_ of the application that
 * will run on it. This should be updated for realistic simulations.</li>
 * <li>When dispatching jobs to VMs, this sample does not consider CPU and
 * memory requirements of the jobs. It just dispatches a job which takes the
 * same time as specified in the trace but the job will always fill the complete
 * VM.</li>
 * <li>Was not tested with any VM consolidation mechanism which might affect the
 * behaviour of various components.</li>
 * </ul>
 * 
 * 
 * @author "Gabor Kecskemeti, Department of Computer Science, Liverpool John
 *         Moores University, (c) 2019"
 */
public class AutoScalingDemo implements TraceExhaustionCallback {

	/**
	 * The virtual infrastructure which will receive the jobs
	 */
	private VirtualInfrastructure vi;
	/**
	 * The energy meter which collects energy performance related information about
	 * the scaling mechanism
	 */
	private IaaSEnergyMeter energymeter;
	/**
	 * The utilisation details of the physical machines before any jobs were
	 * dispatched to them.
	 */
	private HashMap<PhysicalMachine, Double> preProcessingRecords;

	/**
	 * The cloud service which will accommodate the virtual infrastructure
	 */
	private final IaaSService cloud;
	/**
	 * The job handler that recognises when jobs are due and sends them to the cloud
	 * with the help of a job launcher and queueing mechanism.
	 */
	private final JobArrivalHandler jobhandler;

	/**
	 * Callback handler to do finalise the simulation once all jobs have completed.
	 */
	@Override
	public void allJobsFinished() {
		vi.terminateScalingMechanism();
		// There is no need to monitor the IaaS either
		energymeter.stopMeter();
	}

	/**
	 * Initialises the simulation by setting up a cloud infrastructure, and a
	 * virtual infrastructure&job handling mechanism on top of it.
	 * 
	 * @param cores        The number of cores the PMs in the cloud should possess.
	 *                     This should not be less than four at the moment. For
	 *                     details see:
	 *                     {@link VirtualInfrastructure#requestVM(String)}.
	 * @param nodes        The size of the cloud.
	 * @param traceFileLoc The location of the tracefile to be used which will be
	 *                     sent to the virtual infrastructure created and scaled on
	 *                     the cloud specified in the previous parameters.
	 * @throws Exception If the trace cannot be loaded, or if there are some
	 *                   configuration issue.
	 */
	public AutoScalingDemo(int cores, int nodes, String traceFileLoc, Class<? extends VirtualInfrastructure> viclass)
			throws Exception {
		if (cores < 4)
			throw new InvalidParameterException("Per PM core count cannot be lower than 4");
		// Prepares the datacentre
		cloud = DCCreation.createDataCentre(FirstFitScheduler.class, SchedulingDependentMachines.class, nodes, cores);
		// Wait until the PM Controllers finish their initial activities
		Timed.simulateUntilLastEvent();
		// Set up our energy meter for the whole cloud
		energymeter = new IaaSEnergyMeter(cloud);
		// Initialise the virtual infrastructue of ours on the cloud
		System.err.println("Using the auto scaler: " + viclass.getName());
		vi = viclass.getConstructor(IaaSService.class).newInstance(cloud);
		vi.startAutoScaling();

		// Simple job dispatching mechanism which first prepares the workload
		Progress progress = new Progress(this);
		JobLauncher launcher = new FirstFitJobScheduler(vi, progress);
		QueueManager qm = new QueueManager(launcher);
		jobhandler = new JobArrivalHandler(FileBasedTraceProducerFactory.getProducerFromFile(traceFileLoc, 0, 1000000,
				false, nodes * cores, DCFJob.class), launcher, qm, progress);
		jobhandler.processTrace();

		// Collecting basic monitoring information
		preProcessingRecords = new HashMap<PhysicalMachine, Double>();
		for (PhysicalMachine pm : cloud.machines) {
			preProcessingRecords.put(pm, pm.getTotalProcessed());
		}
		// Collects energy related details in every hour
		energymeter.startMeter(3600000, true);
	}

	/**
	 * Start the simulation and print out the statistics about the performance of
	 * the scaling and dispatching mechanisms applied in this simulation.
	 */
	public void simulateAndprintStatistics() {
		long before = System.currentTimeMillis();
		long beforeSimu = Timed.getFireCount();
		// Now we can start the simulation
		Timed.simulateUntilLastEvent();

		// Simulation is done

		// Let's print out some basic statistics
		System.out.println("Simulation took: " + (System.currentTimeMillis() - before) + "ms");
		long simuTimespan = Timed.getFireCount() - beforeSimu;
		System.out.println("Simulated timespan: " + simuTimespan + " simulated ms");

		double totutil = 0;
		for (PhysicalMachine pm : cloud.machines) {
			totutil += (pm.getTotalProcessed() - preProcessingRecords.get(pm))
					/ (simuTimespan * pm.getPerTickProcessingPower());
		}
		System.out.println("Average utilisation of PMs: " + 100 * totutil / cloud.machines.size() + " %");
		System.out.println("Total power consumption: " + energymeter.getTotalConsumption() / 1000 / 3600000 + " kWh");
		System.out.println("Average queue time: " + jobhandler.getAverageQueueTime() + " s");
		System.out.println("Number of virtual appliances registered at the end of the simulation: "
				+ cloud.repositories.get(0).contents().size());
	}

	/**
	 * Sets up and starts the simulation of an auto-scaled virtual infrastructure
	 * for a particular job trace.
	 * 
	 * list of CLI arguments:
	 * <ol>
	 * <li>The trace file</li>
	 * <li>The number of CPU cores a single machine in the cloud should have</li>
	 * <li>The number of physical machines the cloud should have</li>
	 * <li>The auto scaler mechanism to be used in conjunction with the virtual
	 * infrastructure that will run the jobs from the trace</li>
	 * </ol>
	 * 
	 * @param args the CLI arguments
	 * @throws Exception On any issue this application terminates with a stack trace
	 */
	public static void main(String[] args) throws Exception {
		new AutoScalingDemo(Integer.parseInt(args[1]), Integer.parseInt(args[2]), args[0],
				(Class<? extends VirtualInfrastructure>) Class.forName(args[3])).simulateAndprintStatistics();
	}
}