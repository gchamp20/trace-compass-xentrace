/*******************************************************************************
 * Copyright (c) 2018 Ericsson
 *
 * All rights reserved. This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License v1.0 which
 * accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package org.eclipse.tracecompass.jsontrace.core.test.stub;

import java.io.IOException;

import org.eclipse.tracecompass.internal.jsontrace.core.job.SortingJob;
import org.eclipse.tracecompass.tmf.core.trace.ITmfTrace;

/**
 * Stub sorting job for {@link JsonStubTrace}
 *
 * @author Simon Delisle
 */
public class JsonStubTraceSortingJob extends SortingJob {

    /**
     * Constructor
     *
     * @param trace
     *            Trace to sort
     * @param path
     *            Trace path
     */
    public JsonStubTraceSortingJob(ITmfTrace trace, String path) {
        super(trace, path, "\"timestamp\":", 1); //$NON-NLS-1$
    }

    @Override
    protected void processMetadata(ITmfTrace trace, String dir) throws IOException {
        // No metadata to process
    }

}
