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

import java.util.ArrayDeque;


import java.util.ArrayList;
import java.util.HashMap;

import hu.mta.sztaki.lpds.cloud.simulator.Timed;
import hu.mta.sztaki.lpds.cloud.simulator.iaas.IaaSService;
import hu.mta.sztaki.lpds.cloud.simulator.iaas.VMManager.VMManagementException;
import hu.mta.sztaki.lpds.cloud.simulator.iaas.VirtualMachine;
import hu.mta.sztaki.lpds.cloud.simulator.iaas.VirtualMachine.State;
import hu.mta.sztaki.lpds.cloud.simulator.iaas.constraints.ConstantConstraints;
import hu.mta.sztaki.lpds.cloud.simulator.iaas.constraints.ResourceConstraints;
import hu.mta.sztaki.lpds.cloud.simulator.io.Repository;
import hu.mta.sztaki.lpds.cloud.simulator.io.VirtualAppliance;

/**
 * Represents a virtual infrastructure that is scaled automatically based on the
 * needs of the application running on the infrastructure. The class allows
 * subclasses to provide the auto scaling mechanism by providing an
 * implementation of tick method. If the auto scaling mechanism is to be run,
 * the tick method will be called in every two minutes.
 * 
 * @author "Gabor Kecskemeti, Department of Computer Science, Liverpool John
 *         Moores University, (c) 2019"
 */
public abstract class VirtualInfrastructure extends Timed implements VirtualMachine.StateChange {
	/**
	 * The virtual infrastructure for each executable. The keys of the map are the
	 * executable types for which we have a virtual infrastructure. The values of
	 * the map are the actual VMs that we have at hand for a given executable.
	 */
	public final HashMap<String, ArrayList<VirtualMachine>> vmSetPerKind = new HashMap<String, ArrayList<VirtualMachine>>();

	/**
	 * If a kind of VM is under preparation already, we remember it here so we can
	 * tell which VM kind should not have more VMs instantiated/destructued for the
	 * time being.
	 */
	public final HashMap<String, VirtualMachine> underPrepVMPerKind = new HashMap<String, VirtualMachine>();

	/**
	 * The cloud on which we will execute our virtual machines
	 */
	protected final IaaSService cloud;
	/**
	 * The cloud's VMI storage where we register the images of our executables
	 * allowing the instantiation of specific VMs that host the particular
	 * executable.
	 */
	protected final Repository storage;
	/**
	 * The number of cores the first PM (of the cloud) has. This is used to
	 * determine the maximum size of the VMs we will request.
	 */
	private final int pmCores;
	/**
	 * The processing capability of the first PM in the cloud.
	 */
	private final double pmProcessing;
	/**
	 * The amount of memory the first PM (of the cloud) has. This is used to
	 * determine the maximum size of the VMs we will request.
	 */
	private final long pmMem;

	/**
	 * All VMs that we deploy to the cloud are monitored and their CPU utilisation
	 * is used for scaling the virtual infrastructure of a particular application
	 */
	private final HashMap<VirtualMachine, HourlyVMMonitor> vmmonitors = new HashMap<VirtualMachine, HourlyVMMonitor>();

	/**
	 * Allows us to remember which VM kinds fell out of use
	 */
	private final ArrayDeque<String> obsoleteVAs = new ArrayDeque<String>();

	/**
	 * Initialises the auto scaling mechanism
	 * 
	 * @param cloud the physical infrastructure to use to rent the VMs from
	 */
	public VirtualInfrastructure(final IaaSService cloud) {
		this.cloud = cloud;
		storage = cloud.repositories.get(0);
		ResourceConstraints rcForMachine = cloud.machines.get(0).getCapacities();
		pmCores = (int) rcForMachine.getRequiredCPUs();
		pmProcessing = rcForMachine.getRequiredProcessingPower();
		pmMem = rcForMachine.getRequiredMemory();
	}

	/**
	 * If a new executable is encountered for which we might need a set of VMs, we
	 * can communicate this here.
	 * 
	 * @param kind the type of the executable that we need the VMs for
	 */
	public void regNewVMKind(final String kind) {
		if (vmSetPerKind.get(kind) == null) {
			vmSetPerKind.put(kind, new ArrayList<VirtualMachine>());
		}
	}

	/**
	 * Arranges a new VM request to be sent to the cloud. If needed it registers the
	 * corresponding VMI with the cloud. It also prepares all internal data
	 * structures used for monitoring the VM.
	 * 
	 * <b>Warning:</b> The VM's resource requirements are not determined
	 * realistically. If the biggest PM in the infrastructure is having less than 4
	 * CPU cores, then this could lead to VMs that cannot be run on the cloud.
	 * 
	 * @param vmKind The executable for which we want a new VM for.
	 */
	protected void requestVM(final String vmKind) {
		if (underPrepVMPerKind.containsKey(vmKind)) {
			// Ignore the request as we have a VM in preparation already
			return;
		}
		// VMI handling
		VirtualAppliance va = (VirtualAppliance) storage.lookup(vmKind);
		if (va == null) {
			// A random sized VMI with a short boot procedure. The approximate size of the
			// VMI will be 1GB.
			va = new VirtualAppliance(vmKind, 15, 0, true, 1024 * 1024 * 1024);
			boolean regFail;
			do {
				regFail = !storage.registerObject(va);
				if (regFail) {
					// Storage ran out of space, remove the VA that became obsolete the longest time
					// in the past
					if (obsoleteVAs.isEmpty()) {
						throw new RuntimeException(
								"Configured with a repository not big enough to accomodate a the following VA: " + va);
					}
					String vaRemove = obsoleteVAs.pollFirst();
					storage.deregisterObject(vaRemove);
				}
			} while (regFail);
		}

		// VM size is a bit dependent on the VA used
		// This guarantees a deterministic VM resource set for each kind of application
		// The core count of a VM will be between 1-4.
		final int vmScaler = vmKind.length() % 4 + 1;
		try {
			VirtualMachine vm = cloud.requestVM(va,
					new ConstantConstraints(vmScaler, pmProcessing, vmScaler * pmMem / pmCores), storage, 1)[0];
			HourlyVMMonitor vmMonitor = new HourlyVMMonitor(vm);
			vmMonitor.startMon();
			vmmonitors.put(vm, vmMonitor);
			ArrayList<VirtualMachine> vmset = vmSetPerKind.get(vmKind);
			if (vmset.isEmpty()) {
				// VA became used again, no longer obsolete
				obsoleteVAs.remove(vmKind);
			}
			vmset.add(vm);
			underPrepVMPerKind.put(vmKind, vm);
			vm.subscribeStateChange(this);
		} catch (Exception vmm) {
			vmm.printStackTrace();
			System.exit(1);
		}
	}

	/**
	 * Makes sure that the given VM is no longer having any local monitoring
	 * mechanism running and also that it is no longer present at the cloud.
	 * 
	 * @param vm The VM to be destroyed.
	 */
	protected void destroyVM(final VirtualMachine vm) {
		vmmonitors.remove(vm).finishMon();
		try {
			final String vmKind = vm.getVa().id;
			ArrayList<VirtualMachine> vms = vmSetPerKind.get(vmKind);
			vms.remove(vm);
			underPrepVMPerKind.remove(vmKind);
			if (VirtualMachine.State.DESTROYED.equals(vm.getState())) {
				// The VM was not even running when the decision about its destruction was made
				cloud.terminateVM(vm, true);
			} else {
				// The VM was initiated on the cloud, but we no longer need it
				vm.destroy(true);
			}
			if (vms.isEmpty()) {
				// Last use of the VA, make it obsolete now => enable it to be removed from the
				// central storage
				obsoleteVAs.add(vmKind);
			}
		} catch (VMManagementException e) {
			// Should not really happen
			e.printStackTrace();
			System.exit(1);
		}
	}

	protected double getHourlyUtilisationPercForVM(VirtualMachine vm) {
		return vmmonitors.get(vm).getHourlyUtilisationPerc();
	}

	/**
	 * Asks Timed to notify us every two minutes. The auto scaling mechanism will be
	 * running with this frequency.
	 */
	public void startAutoScaling() {
		subscribe(120 * 1000);
	}

	/**
	 * If there are no further tasks for the virtual infrastructure, it is
	 * completely dismantled and the two minute notifications from Timed are
	 * cancelled.
	 */
	public void terminateScalingMechanism() {
		// No VMs have tasks running, all jobs finished, we don't have to work on the
		// infrastructure anymore.
		for (ArrayList<VirtualMachine> vmset : vmSetPerKind.values()) {
			while (!vmset.isEmpty()) {
				destroyVM(vmset.get(vmset.size() - 1));
			}
		}
		unsubscribe();
		System.out.println("Autoscaling mechanism terminated.");
	}

	/**
	 * If a VM starts to run, we should enable further VMs to start or destruct
	 */
	@Override
	public void stateChanged(final VirtualMachine vm, final State oldState, final State newState) {
		if (VirtualMachine.State.RUNNING.equals(newState)) {
			underPrepVMPerKind.remove(vm.getVa().id);
			vm.unsubscribeStateChange(this);
		} else if (VirtualMachine.State.NONSERVABLE.equals(newState)) {
			underPrepVMPerKind.remove(vm.getVa().id);
			vm.unsubscribeStateChange(this);
		}
	}
}
