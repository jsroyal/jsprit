/*
 * Licensed to GraphHopper GmbH under one or more contributor
 * license agreements. See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 *
 * GraphHopper GmbH licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.graphhopper.jsprit.core.algorithm.ruin;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.cost.VehicleRoutingTransportCosts;
import com.graphhopper.jsprit.core.problem.job.Job;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.JobActivity;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;
import com.graphhopper.jsprit.core.util.RandomNumberGeneration;
import com.graphhopper.jsprit.core.util.RandomUtils;
import org.apache.commons.math3.ml.clustering.Cluster;
import org.apache.commons.math3.ml.clustering.Clusterable;
import org.apache.commons.math3.ml.distance.DistanceMeasure;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by schroeder on 04/02/15.
 */
public class DBSCANClusterer {

    private static class LocationWrapper implements Clusterable {

        private static int objCounter = 0;

        private final Job job;

        private List<Location> locations;

        private int id;

        public LocationWrapper(Job job, List<Location> locations) {
            this.locations = locations;
            objCounter++;
            this.job = job;
            this.id = objCounter;
        }


        public List<Location> getLocations() {
            return locations;
        }

        @Override
        public double[] getPoint() {
            return new double[]{id};
        }

        public Job getJob() {
            return job;
        }
    }

    private static class MyDistance implements DistanceMeasure {

        private Map<Integer, LocationWrapper> locations;

        private VehicleRoutingTransportCosts costs;

        public MyDistance(List<LocationWrapper> locations, VehicleRoutingTransportCosts costs) {
            this.locations = new HashMap<>();
            for (LocationWrapper lw : locations) {
                this.locations.put((int) lw.getPoint()[0], lw);
            }
            this.costs = costs;
        }

        @Override
        public double compute(double[] doubles, double[] doubles1) {
            LocationWrapper l1 = locations.get((int) doubles[0]);
            LocationWrapper l2 = locations.get((int) doubles1[0]);
            int count = 0;
            double sum = 0;
            for (Location loc_1 : l1.getLocations()) {
                for (Location loc_2 : l2.getLocations()) {
                    sum += costs.getTransportCost(loc_1, loc_2, 0, null, null);
                    count++;
                }
            }
            return sum / (double) count;
        }
    }

    private VehicleRoutingTransportCosts costs;

    private int minNoOfJobsInCluster = 1;

    private int noDistanceSamples = 10;

    private double epsFactor = 0.8;

    private Double epsDistance;

    private Random random = RandomNumberGeneration.getRandom();

    public void setRandom(Random random) {
        this.random = random;
    }

    public DBSCANClusterer(VehicleRoutingTransportCosts costs) {
        this.costs = costs;
    }

    public void setMinPts(int pts) {
        this.minNoOfJobsInCluster = pts;
    }

    public void setEpsFactor(double epsFactor) {
        this.epsFactor = epsFactor;
    }

    public void setEpsDistance(double epsDistance) {
        this.epsDistance = epsDistance;
    }

    public List<List<Job>> getClusters(VehicleRoute route) {
        List<LocationWrapper> locations = getLocationWrappers(route);
        List<Cluster<LocationWrapper>> clusterResults = getClusters(route, locations);
        return makeList(clusterResults);
    }

    private List<LocationWrapper> getLocationWrappers(VehicleRoute route) {
        List<LocationWrapper> locations = new ArrayList<>(route.getTourActivities().getJobs().size());
        Map<Job, List<Location>> jobs2locations = new HashMap<>();
        route.getActivities().stream().filter(act -> act instanceof JobActivity).forEach(act -> {
            Job job = ((JobActivity) act).getJob();
            if (!jobs2locations.containsKey(job)) {
                jobs2locations.put(job, new ArrayList<>());
            }
            jobs2locations.get(job).add(act.getLocation());
        });
        locations.addAll(jobs2locations.keySet().stream().map(j -> new LocationWrapper(j, jobs2locations.get(j))).collect(Collectors.toList()));
        return locations;
    }

    private List<Cluster<LocationWrapper>> getClusters(VehicleRoute route, List<LocationWrapper> locations) {
        double sampledDistance;
        if (epsDistance != null) sampledDistance = epsDistance;
        else sampledDistance = Math.max(0, sample(costs, route));
        org.apache.commons.math3.ml.clustering.DBSCANClusterer<LocationWrapper> clusterer = new org.apache.commons.math3.ml.clustering.DBSCANClusterer<LocationWrapper>(sampledDistance, minNoOfJobsInCluster, new MyDistance(locations, costs));
        return clusterer.cluster(locations);
    }

    private List<List<Job>> makeList(List<Cluster<LocationWrapper>> clusterResults) {
        List<List<Job>> l = new ArrayList<>();
        for (Cluster<LocationWrapper> c : clusterResults) {
            List<Job> l_ = getJobList(c);
            l.add(l_);
        }
        return l;
    }

    private List<Job> getJobList(Cluster<LocationWrapper> c) {
        List<Job> l_ = new ArrayList<>();
        if (c == null) return l_;
        l_.addAll(c.getPoints().stream().map(LocationWrapper::getJob).collect(Collectors.toList()));
        return l_;
    }

    public List<Job> getRandomCluster(VehicleRoute route) {
        if (route.isEmpty()) return Collections.emptyList();
        List<LocationWrapper> locations = getLocationWrappers(route);
        List<Cluster<LocationWrapper>> clusterResults = getClusters(route, locations);
        if (clusterResults.isEmpty()) return Collections.emptyList();
        Cluster<LocationWrapper> randomCluster = RandomUtils.nextItem(clusterResults, random);
        return getJobList(randomCluster);
    }

    private double sample(VehicleRoutingTransportCosts costs, VehicleRoute r) {
        double min = Double.MAX_VALUE;
        double sum = 0;
        for (int i = 0; i < noDistanceSamples; i++) {
            TourActivity act1 = RandomUtils.nextItem(r.getActivities(), random);
            TourActivity act2 = RandomUtils.nextItem(r.getActivities(), random);
            double dist = costs.getTransportCost(act1.getLocation(), act2.getLocation(),
                0., null, r.getVehicle());
            if (dist < min) min = dist;
            sum += dist;
        }
        double avg = sum / ((double) noDistanceSamples);
        return (avg - min) * epsFactor;
    }

}
