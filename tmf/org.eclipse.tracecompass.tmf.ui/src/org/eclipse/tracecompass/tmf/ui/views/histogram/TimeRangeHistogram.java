/*******************************************************************************
 * Copyright (c) 2011, 2014 Ericsson
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License v1.0 which
 * accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Francois Chouinard - Initial API and implementation
 *   Bernd Hufmann - Changed to updated histogram data model
 *   Francois Chouinard - Moved from LTTng to TMF
 *   Patrick Tasse - Update for mouse wheel zoom
 *******************************************************************************/

package org.eclipse.tracecompass.tmf.ui.views.histogram;

import java.util.Objects;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Composite;

/**
 * A basic histogram widget that displays the event distribution of a specific
 * time range of a trace. It has the following additional features:
 * <ul>
 * <li>zoom in: mouse wheel up (or forward)
 * <li>zoom out: mouse wheel down (or backward)
 * </ul>
 *
 * @version 1.1
 * @author Francois Chouinard
 */
public class TimeRangeHistogram extends Histogram {

    // ------------------------------------------------------------------------
    // Attributes
    // ------------------------------------------------------------------------

    private HistogramZoom fZoom = null;

    private long fRangeStartTime = 0L;
    private long fRangeDuration;
    private long fFullRangeStartTime = 0L;
    private long fFullRangeEndTime = 0L;

    // ------------------------------------------------------------------------
    // Constructor
    // ------------------------------------------------------------------------
    /**
     * Constructor.
     *
     * @param view
     *            A reference to the parent TMF view.
     * @param parent
     *            A parent composite
     */
    public TimeRangeHistogram(HistogramView view, Composite parent) {
        this(view, parent, false);
    }

    /**
     * Full Constructor
     *
     * @param view
     *            The parent histogram view
     * @param parent
     *            The parent composite
     * @param sendTimeAlignSignals
     *            Flag to send time alignment signals or not
     * @since 1.0
     */
    public TimeRangeHistogram(HistogramView view, Composite parent, boolean sendTimeAlignSignals) {
        super(view, parent, sendTimeAlignSignals);
        fZoom = new HistogramZoom(this, getStartTime(), getTimeLimit());
    }

    // ------------------------------------------------------------------------
    // Operations
    // ------------------------------------------------------------------------

    @Override
    public synchronized void clear() {
        fRangeStartTime = 0L;
        fRangeDuration = 0L;
        fFullRangeEndTime = 0L;
        setOffset(0);
        if (fZoom != null) {
            fZoom.setFullRange(0L, 0L);
            fZoom.setNewRange(0L, 0L);
        }
        super.clear();
    }

    /**
     * Sets the time range of the histogram
     *
     * @param startTime
     *            The start time
     * @param duration
     *            The duration of the time range
     */
    public synchronized void setTimeRange(long startTime, long duration) {
        fRangeStartTime = startTime;
        fRangeDuration = duration;
        fZoom.setNewRange(startTime, duration);
        if (getDataModel().getNbEvents() == 0) {
            getDataModel().setTimeRange(startTime, startTime + duration);
            getDataModel().setEndTime(startTime + duration);
        }
    }

    /**
     * Sets the full time range of the whole trace.
     *
     * @param startTime
     *            The start time
     * @param endTime
     *            The end time
     */
    public void setFullRange(long startTime, long endTime) {
        fFullRangeStartTime = startTime;
        fFullRangeEndTime = endTime;
        fZoom.setFullRange(startTime, endTime);
        fZoom.setNewRange(fRangeStartTime, fRangeDuration);
    }

    // ------------------------------------------------------------------------
    // MouseListener
    // ------------------------------------------------------------------------

    private int fStartPosition;
    private int fMaxOffset;
    private int fMinOffset;

    @Override
    public void mouseDown(MouseEvent event) {
        final long endTime = fDataModel.getEndTime();
        final long startTime = fDataModel.getStartTime();
        if (fScaledData != null && fDragState == DRAG_NONE && startTime < endTime) {
            if (event.button == 2 || (event.button == 1 && (event.stateMask & SWT.MODIFIER_MASK) == SWT.CTRL)) {
                fDragState = DRAG_RANGE;
                fDragButton = event.button;
                fStartPosition = event.x;
                long maxOffset = (long) ((startTime - fFullRangeStartTime) / fScaledData.fBucketDuration);
                long minOffset = (long) ((endTime - fFullRangeEndTime) / fScaledData.fBucketDuration);
                fMaxOffset = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, maxOffset));
                fMinOffset = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, minOffset));
                return;
            } else if (event.button == 3) {
                fDragState = DRAG_ZOOM;
                fDragButton = event.button;
                fRangeStartTime = getTimestamp(event.x);
                fRangeDuration = 0;
                fCanvas.redraw();
                return;
            }
        }
        super.mouseDown(event);
    }

    @Override
    public void mouseUp(MouseEvent event) {
        if (fDragState == DRAG_RANGE && event.button == fDragButton) {
            fDragState = DRAG_NONE;
            fDragButton = 0;
            if (event.x != fStartPosition) {
                int pointDelta = event.x - fStartPosition;
                long deltaNs = (long) (pointDelta * fScaledData.fBucketDuration);
                long startTime = fRangeStartTime - deltaNs;
                fRangeStartTime = Math.max(fFullRangeStartTime, Math.min(fFullRangeEndTime - fRangeDuration, startTime));
                ((HistogramView) fParentView).updateTimeRange(fRangeStartTime, fRangeStartTime + fRangeDuration);
                setOffset(0);
            }
            return;
        } else if (fDragState == DRAG_ZOOM && event.button == fDragButton) {
            fDragState = DRAG_NONE;
            fDragButton = 0;
            if (fRangeDuration < 0) {
                fRangeStartTime = fRangeStartTime + fRangeDuration;
                fRangeDuration = -fRangeDuration;
            }
            if (fRangeDuration > 0) {
                ((HistogramView) fParentView).updateTimeRange(fRangeStartTime, fRangeStartTime + fRangeDuration);
            } else {
                fRangeStartTime = fZoom.getStartTime();
                fRangeDuration = fZoom.getDuration();
                fCanvas.redraw();
            }
            return;
        }
        super.mouseUp(event);
    }

    // ------------------------------------------------------------------------
    // MouseMoveListener
    // ------------------------------------------------------------------------

    @Override
    public void mouseMove(MouseEvent event) {
        if (fDragState == DRAG_RANGE) {
            int offset = Math.max(fMinOffset, Math.min(fMaxOffset, event.x - fStartPosition));
            setOffset(offset);
            fCanvas.redraw();
            return;
        } else if (fDragState == DRAG_ZOOM) {
            long endTime = Math.max(getStartTime(), Math.min(getEndTime(), getTimestamp(event.x)));
            fRangeDuration = endTime - fRangeStartTime;
            fCanvas.redraw();
            return;
        }
        super.mouseMove(event);
    }

    // ------------------------------------------------------------------------
    // PaintListener
    // ------------------------------------------------------------------------

    @Override
    public void paintControl(PaintEvent event) {
        super.paintControl(event);

        if (fDragState == DRAG_ZOOM) {
            Image image = Objects.requireNonNull((Image) fCanvas.getData(IMAGE_KEY));

            Image rangeRectangleImage = new Image(image.getDevice(), image, SWT.IMAGE_COPY);
            GC rangeWindowGC = new GC(rangeRectangleImage);

            drawTimeRangeWindow(rangeWindowGC, fRangeStartTime, fRangeDuration);

            // Draws the buffer image onto the canvas.
            event.gc.drawImage(rangeRectangleImage, 0, 0);

            rangeWindowGC.dispose();
            rangeRectangleImage.dispose();
        }
    }

}
