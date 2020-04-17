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

import java.util.Collections;
import java.util.List;

import hu.mta.sztaki.lpds.cloud.simulator.Timed;
import hu.mta.sztaki.lpds.cloud.simulator.helpers.job.Job;
import hu.mta.sztaki.lpds.cloud.simulator.helpers.job.JobListAnalyser;
import hu.mta.sztaki.lpds.cloud.simulator.helpers.trace.GenericTraceProducer;
import hu.mta.sztaki.lpds.cloud.simulator.helpers.trace.TraceManagementException;
import hu.

/**
 * Processes a trace and sends its jobs to a job launcher. If a job cannot be
 * launched at the moment, it will queue it with the help of a queue manager.
 * 
 * @author "Gabor Kecskemeti, Department of Computer Science, Liverpool John
 *         Moores University, (c) 2019"
 */
public class JobArrivalHandler extends Timed {
	/**
	 * All jobs to be handled
	 */
	private final List<Job> jobs;
	private final int totaljobcount;
	/**
	 * The job scheduler to be used
	 */
	private final JobLauncher launcher;
	/**
	 * Queue where we can send jobs that cannot be executed right away
	 */
	private final QueueManager qm;
	/**
	 * The job to be executed next
	 */
	private int currIndex = 0;

	/**
	 * Loads the trace and analyses it to prepare all its jobs for scheduling.
	 * 
	 * @param trace    The trace to be process by this handler
	 * @param launcher The job scheduling mechanism to be used when a job is due to
	 *                 be ran.
	 * @param qm       The queueing mechanism if the job scheduler rejects the job
	 *                 at the moment.
	 * @param pr       The state exchange mechanism
	 * @throws TraceManagementException If there was an issue during the trace's
	 *                                  loading
	 */
	public JobArrivalHandler(final GenericTraceProducer trace, final JobLauncher launcher, final QueueManager qm,
			final Progress pr) throws TraceManagementException {
		// Preparing the workload
		jobs = trace.getAllJobs();
		System.out.println("Number of loaded jobs: " + jobs.size());
		// Ensuring they are listed in submission order
		Collections.sort(jobs, JobListAnalyser.submitTimeComparator);
		// Analyzing the jobs for min and max submission time
		long minsubmittime = JobListAnalyser.getEarliestSubmissionTime(jobs);
		final long currentTime = Timed.getFireCount();
		final long msTime = minsubmittime * 1000;
		if (currentTime > msTime) {
			final long adjustTime = (long) Math.ceil((currentTime - msTime) / 1000f);
			minsubmittime += adjustTime;
			for (Job job : jobs) {
				job.adjust(adjustTime);
			}
		}
		// Workload prepared we need to move the timer till the first job is due.
		Timed.skipEventsTill(minsubmittime * 1000);
		this.launcher = launcher;
		this.qm = qm;
		totaljobcount = jobs.size();
		pr.setTotalJobCount(totaljobcount);
	}

	/**
	 * Starts the trace processing mechanism
	 */
	public void processTrace() {
		tick(Timed.getFireCount());
	}

	/**
	 * Checks if a job is due at the moment, if so, it dispatches it. If it cannot
	 * be dispatched, it queues it.
	 */
	@Override
	public void tick(long currTime) {
		for (int i = currIndex; i < totaljobcount; i++) {
			final Job toprocess = jobs.get(i);
			final long submit = toprocess.getSubmittimeSecs() * 1000;
			if (currTime == submittime) {
				// Job is due
				if (launcher.launchAJob(toprocess)) {
					// infra was not capable to host the job, let's queue it
					qm.add(toprocess);
				}
				currIndex = i + 1;
			} else if (currTime < submittime) {
				// Nothing to do now, let's wait till the next job is due
				updateFrequency(submittime - currTime);
				return;
			}
		}
		if (currIndex == totaljobcount) {
			// No further jobs, so no further dispatching
			System.out.println("Last job arrived, dispatching mechanism is terminated.");
			unsubscribe();
		}
	}

	/**
	 * An aggregate queue time metric can be queried here to tell how did the
	 * virtual infrastructure performed. This should be queried only after all jobs
	 * have completed.
	 * 
	 * @return the average queue time of all jobs in the trace.
	 */
	public double getAverageQueueTime() {
		double totqt = 0;
		for (int i = 0; i < totaljobcount; i++) {
			totqt += jobs.get(i).getRealqueueTime();
		}
		return totqt / jobs.size();
	}
}
