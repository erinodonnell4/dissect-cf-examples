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

import java.util.ArrayList;


import hu.mta.sztaki.lpds.cloud.simulator.helpers.job.Job;
import hu.mta.sztaki.lpds.cloud.simulator.iaas.VirtualMachine;
import hu.mta.sztaki.lpds.cloud.simulator.iaas.resourcemodel.ResourceConsumption;
import hu.mta.sztaki.lpds.cloud.simulator.iaas.resourcemodel.ResourceConsumption.ConsumptionEvent;
import hu.mta.sztaki.lpds.cloud.simulator.io.NetworkNode.NetworkException;

/**
 * Offers a simple JobLauncher implementation based on the first fit principle.
 * If there are no VMs that can accommodate a particular VM at the time, it
 * rejects the job, it is then the caller's responsibility to decide what to do
 * with the job.
 * 
 * @author "Gabor Kecskemeti, Department of Computer Science, Liverpool John
 *         Moores University, (c) 2019"
 */
public class FirstFitJobScheduler implements JobLauncher, ConsumptionEvent {

	/**
	 * The virtual infrastructure this launcher will target with its jobs.
	 */
	private final VirtualInfrastructure vi;
	/**
	 * The object which will receive the progress updates about the various job
	 * related activities.
	 */
	private final Progress progress;

	/**
	 * Constructs the scheduler and saves the input data so the scheduler will know
	 * where to submit jobs and where to report their statuses.
	 * 
	 * @param vi The infrastructure to use for the execution of the given tasks.
	 * @param pr The object to report the job statuses.
	 */
	public FirstFitJobScheduler(final VirtualInfrastructure vi, final Progress pr) {
		this.vi = vi;
		this.progress = pr;
	}

	/**
	 * The actual job dispatching mechanism. If there is no VM to accommodate the
	 * job, this launcher tells the VI that it needs a VM of the kind which matches
	 * up with the executable of the job.
	 * 
	 * @param j the job to be sent to one of the VM's in the virtual infrastructure
	 * @return <i>true</i> if the job cannot be assigned to any of the
	 *         infrastruture's VMs. <i>false</i> if the job was taken care of and
	 *         there is no further action needed on it.
	 */
	@Override
	public boolean launchAJob(final Job j) {
		try {
			final ArrayList<VirtualMachine> vmset = vi.vmSetPerKind.get(j.executable);
			if (vmset != null) {
				final int vmsetsize = vmset.size();
				for (int i = 0; i < vmsetsize; i++) {
					final VirtualMachine vm = vmset.get(i);
					if (VirtualMachine.State.RUNNING.equals(vm.getState()) && vm.underProcessing.isEmpty()
							&& vm.toBeAdded.isEmpty()) {
						// VM has the executable and does not do a thing, ready to accept the job

						// Ignores the processor count of the task, assumes that the full VM will be
						// used all the time
						vm.newComputeTask(j.getExectimeSecs() * 1000 * vm.getPerTickProcessingPower(),
								ResourceConsumption.unlimitedProcessing, this);
						progress.registerDispatch();
						j.started();

						// Task is now on the VM. We will receive a conComplete message if it is done.
						return false;
					}
				}
			} else {
				// The job's executable is not supported by any VMs. We need to ask the VI to
				// manage VMs with this kind of executable as well.
				vi.regNewVMKind(j.executable == null ? "default" : j.executable);
			}

		} catch (NetworkException ne) {
			ne.printStackTrace();
			// Not expected
			System.exit(1);
		}
		return true;
	}

	/**
	 * If a job is done, it's completion event will be registered as a status update
	 * against our {@link #progress} object.
	 */
	@Override
	public void conComplete() {
		progress.registerCompletion();
	}

	/**
	 * If a job is not executed correctly we would receive this message. In the
	 * applied setup here, we cannot have a notification like this so it is ignored.
	 */
	@Override
	public void conCancelled(ResourceConsumption problematic) {
		// Ignore
	}

}
