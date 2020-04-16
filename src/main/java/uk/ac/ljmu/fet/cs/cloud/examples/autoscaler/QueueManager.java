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


import java.util.HashMap;
import java.util.Iterator;

import hu.mta.sztaki.lpds.cloud.simulator.Timed;
import hu.mta.sztaki.lpds.cloud.simulator.helpers.job.Job;

/**
 * Allows a jobs to be queued if it was not possible to find an acceptable VM
 * for it at a given moment. This class will periodically retry starting the job
 * with the help of a specified job launcher.
 * 
 * This class does not provide any queue reordering features (e.g., job
 * priorities, and other QoS metrics)
 * 
 * @author "Gabor Kecskemeti, Department of Computer Science, Liverpool John
 *         Moores University, (c) 2019"
 */
class QueueManager extends Timed {

	/**
	 * The job launcher to with which we can do the job re-submissions
	 */
	private final JobLauncher launcher;

	/**
	 * The actual queue. Key: application kind. Value: the list of jobs waiting
	 * there.
	 */
	private final HashMap<String, ArrayDeque<Job>> queued = new HashMap<String, ArrayDeque<Job>>();

	/**
	 * Saves the input parameter
	 * 
	 * @param launcher The job scheduling mechanism to be used when a job retry is
	 *                 needed.
	 */
	public QueueManager(final JobLauncher launcher) {
		this.launcher = launcher;
	}

	/**
	 * Allows queueing a job, makes sure the queue manager receives updates in every
	 * ten seconds if there are any jobs on the queue.
	 * 
	 * @param j The job to be queued
	 */
	public void add(final Job j) {
		ArrayDeque<Job> q = queued.get(j.executable);
		if (q == null) {
			q = new ArrayDeque<Job>();
			queued.put(j.executable, q);
		}
		q.push(j);
		if (!isSubscribed()) {
			subscribe(10000);
		}
	}

	/**
	 * The queue management algorithm will attempt to launch a job if it is first in
	 * its executable's queue.
	 */
	@Override
	public void tick(final long fires) {
		final Iterator<String> kindIter = queued.keySet().iterator();
		queueChangeLoop: while (kindIter.hasNext()) {
			// The queue for a specific kind of executable
			final ArrayDeque<Job> q = queued.get(kindIter.next());
			do {
				// Launch the current head of the queue
				if (launcher.launchAJob(q.peekFirst())) {
					// Launch was not successful, no point trying further jobs in the queue
					continue queueChangeLoop;
				} else {
					// Removes the head as we were successful in dispatching it to the
					// infrastructure
					q.pollFirst();
				}
			} while (!q.isEmpty());
			kindIter.remove();
		}
		if (queued.size() == 0) {
			unsubscribe();
		}
	}
}