/********************************************************************************
 * Copyright (C) 2026 EduIDE and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License v. 2.0 are satisfied: GNU General Public License, version 2
 * with the GNU Classpath Exception which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 ********************************************************************************/
package org.eclipse.theia.cloud.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SessionStartupHistogramTests {

    @Test
    @SuppressWarnings("unchecked")
    void observe_fillsCumulativeBucketsPerOutcome() {
        Map<String, Supplier<Number>> gauges = new HashMap<>();
        // Captures every gauge registration, whether Mockito hands the tags over expanded or as an array.
        MetricRegistry registry = Mockito.mock(MetricRegistry.class, invocation -> {
            if (!"gauge".equals(invocation.getMethod().getName())) {
                return null;
            }
            Object[] args = invocation.getArguments();
            List<Object> tags = new ArrayList<>();
            for (int i = 2; i < args.length; i++) {
                if (args[i] instanceof Tag[] array) {
                    tags.addAll(Arrays.asList(array));
                } else {
                    tags.add(args[i]);
                }
            }
            StringBuilder key = new StringBuilder((String) args[0]);
            for (Object o : tags) {
                Tag tag = (Tag) o;
                key.append(',').append(tag.getTagName()).append('=').append(tag.getTagValue());
            }
            gauges.put(key.toString(), (Supplier<Number>) args[1]);
            return null;
        });
        SessionStartupHistogram histogram = new SessionStartupHistogram();
        histogram.metricRegistry = registry;

        histogram.observe("java-17", "ns", true, 4);
        histogram.observe("java-17", "ns", true, 40);
        histogram.observe("java-17", "ns", false, 200);

        String ok = ",app_definition=java-17,namespace=ns,outcome=success";
        assertEquals(0L, gauges.get(SessionStartupHistogram.NAME + ".bucket" + ok + ",le=2").get());
        assertEquals(1L, gauges.get(SessionStartupHistogram.NAME + ".bucket" + ok + ",le=5").get());
        assertEquals(2L, gauges.get(SessionStartupHistogram.NAME + ".bucket" + ok + ",le=45").get());
        assertEquals(2L, gauges.get(SessionStartupHistogram.NAME + ".bucket" + ok + ",le=+Inf").get());
        assertEquals(44.0, gauges.get(SessionStartupHistogram.NAME + ".sum" + ok).get().doubleValue());
        assertEquals(1L, gauges.get(SessionStartupHistogram.NAME + ".count,app_definition=java-17,namespace=ns,outcome=error")
                .get());
    }
}
