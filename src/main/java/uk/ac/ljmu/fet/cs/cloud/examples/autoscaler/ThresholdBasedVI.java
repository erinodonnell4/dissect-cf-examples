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

/**
 * The class applies a simple threshold based scaling mechanism: it removes VMs
 * which are not utilised to a certain level; and adds VMs to the VI if most of
 * the VMs are too heavily utilised.
 * 
 * @author "Gabor Kecskemeti, Department of Computer Science, Liverpool John
 *         Moores University, (c) 2019"
 */
public class ThresholdBasedVI extends VirtualInfrastructure {
	/**
	 * Minimum CPU utilisation percentage of a single VM that is still acceptable to
	 * keep in the virtual infrastructure.
	 */
	public static final double minUtilisationLevelBeforeDestruction = .1;
	/**
	 * Maximum average CPU utilisation percentage of all VMs of a particular kind of
	 * executable that is still considered acceptable (i.e., under this utilisation,
	 * the virtual infrastructure does not need a new VM with the same kind of
	 * executable).
	 */
	public static final double maxUtilisationLevelBeforeNewVM = .85;

	/**
	 * We keep track of how many times we found the last VM completely unused for an
	 * particular executable
	 */
	private final HashMap<VirtualMachine, Integer> unnecessaryHits = new HashMap<VirtualMachine, Integer>();

	/**
	 * Initialises the auto scaling mechanism
	 * 
	 * @param cloud the physical infrastructure to use to rent the VMs from
	 */
	public ThresholdBasedVI(final IaaSService cloud) {
		super(cloud);
	}

	/**
	 * The auto scaling mechanism that is run regularly to determine if the virtual
	 * infrastructure needs some changes. The logic is the following:
	 * <ul>
	 * <li>if a VM has less than a given utilisation, then it is destroyed (unless
	 * it is the last VM for a given executable)</li>
	 * <li>if a VM is the last for a given executable, it is given an hour to
	 * receive a new job before it is destructed. <i>After this, one has to
	 * re-register the VM kind to receive new VMs.</i></li>
	 * <li>if an executable was just registered, it will receive a single new
	 * VM.</li>
	 * <li>if all the VMs of a given executable experience an average utilisation of
	 * a given minimum value, then a new VM is created.</li>
	 * </ul>
	 */
	@Override
	public void tick(long fires) {
		// Regular operation, the actual "autoscaler"

		// Determining if we need to get rid of a VM:
		Iterator<String> kinds = vmSetPerKind.keySet().iterator();
		while (kinds.hasNext()) {
			String kind = kinds.next();
			ArrayList<VirtualMachine> vmset = vmSetPerKind.get(kind);
			// Determining if we need a brand new kind of VM:
			if (vmset.isEmpty()) {
				// No VM with this kind yet, we need at least one for each so let's create one
				requestVM(kind);
				continue;
			} else if (vmset.size() == 1) {
				final VirtualMachine onlyMachine = vmset.get(0);
				// We will try to not destroy the last VM from any kind
				if (onlyMachine.underProcessing.isEmpty() && onlyMachine.toBeAdded.isEmpty()) {
					// It has no ongoing computation
					Integer i = unnecessaryHits.get(onlyMachine);
					if (i == null) {
						unnecessaryHits.put(onlyMachine, 1);
					} else {
						i++;
						if (i < 30) {
							unnecessaryHits.put(onlyMachine, i);
						} else {
							// After an hour of disuse, we just drop the VM
							unnecessaryHits.remove(onlyMachine);
							destroyVM(onlyMachine);
							kinds.remove();
						}
					}
					// We don't need to check if we need more VMs as it has no computation
					continue;
				}
				// The VM now does some stuff now so we make sure we don't try to remove it
				// prematurely
				unnecessaryHits.remove(onlyMachine);
				// Now we allow the check if we need more VMs.
			} else {
				boolean destroyed = false;
				for (int i = 0; i < vmset.size(); i++) {
					final VirtualMachine vm = vmset.get(i);
					if (vm.underProcessing.isEmpty() && vm.toBeAdded.isEmpty()) {
						// The VM has no task on it at the moment, good candidate
						if (getHourlyUtilisationPercForVM(vm) < minUtilisationLevelBeforeDestruction) {
							// The VM's load was under 20% in the past hour, we might be able to get rid of
							// it
							destroyVM(vm);
							destroyed = true;
							i--;
						}
					}
				}
				if (destroyed) {
					// No need to check the average workload now, as we just destroyed a VM..
					continue;
				}
			}
			// We need to check if we need more VMs than we have at the moment
			double subHourUtilSum = 0;
			for (VirtualMachine vm : vmset) {
				subHourUtilSum += getHourlyUtilisationPercForVM(vm);
			}
			if (subHourUtilSum / vmset.size() > maxUtilisationLevelBeforeNewVM) {
				// Average utilisation of VMs are over threshold, we need a new one
				requestVM(kind);
			}
		}
	}
}
