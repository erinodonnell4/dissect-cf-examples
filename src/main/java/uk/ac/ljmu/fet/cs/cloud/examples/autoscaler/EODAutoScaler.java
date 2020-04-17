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
		
		//applications which  REQUIRE  virtual infrastructure 
		
		final Iterator<String> kinds = vmSetPerKind.keySet().iterator();
		
		 while (kinds.hasNext()) {
			final String kind = kinds.next();
			final ArrayList<VirtualMachine> vmset = vmSetPerKind.get(kind);
			
			// Checking VM set size
			
			if (vmset.size() > 0) {
				if (vmset.size() < 3) {
					requestVM(kind);
				}
			}
			 
			// If VM is empty - request new VM to support tasks.
			else if (vmset.isEmpty()) {
				requestVM(kind);
			}
			
			// If VM okay - set VM state to Running.
			
			else for (VirtualMachine vm : vmset) {	
				if (vm.getState().equals(State.RUNNING)) {
					
					// This will destroy any empty or unneeded VM's.
					
					if (vm.underProcessing.isEmpty() && vm.toBeAdded.isEmpty()) {
						destroyVM(vm);
					}
				}
			}
		}
	}
}	}
