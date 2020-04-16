package uk.ac.ljmu.fet.cs.cloud.examples.autoscaler;

import java.util.ArrayList;
import java.util.Iterator;

import hu.mta.sztaki.lpds.cloud.simulator.iaas.IaaSService;
import hu.mta.sztaki.lpds.cloud.simulator.iaas.VirtualMachine;
import hu.mta.sztaki.lpds.cloud.simulator.iaas.VirtualMachine.State;

public class EODAutoScaler extends VirtualInfrastructure {

	public EODAutoScaler(IaaSService cloud) {
		super(cloud);
	}
	
	
	@Override
	public void tick(long fires) {
		
		// Applications which require virtual infrastructure 
		
		final Iterator<String> kinds = vmSetPerKind.keySet().iterator();
		
		 while (kinds.hasNext()) {
			final String kind = kinds.next();
			final ArrayList<VirtualMachine> vmset = vmSetPerKind.get(kind);
			
			if (vmset.size() > 0) {
				if (vmset.size() < 3) {
					requestVM(kind);
				}
			}
			
			else if (vmset.isEmpty()) {
				requestVM(kind);
			}
			
			else for (VirtualMachine vm : vmset) {	
				if (vm.getState().equals(State.RUNNING)) {
					
					// Will destroy the VM if empty and there are no tasks.
					
					if (vm.underProcessing.isEmpty() && vm.toBeAdded.isEmpty()) {
						destroyVM(vm);
					}
				}
			}
		}
	}
}	}
