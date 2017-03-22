/*******************************************************************************
 * Copyright (c) 2016 Ericsson
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License v1.0 which
 * accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package org.eclipse.tracecompass.common.core.log;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.tracecompass.common.core.NonNullUtils;

/**
 * Logger helper
 *
 * This is a logger helper, it will allow entry-exit analysis to be much easier.
 *
 * The events are saved in a JSON-like message in the phase of the event. It is
 * an event type but with extra information associated to it. Typical types can
 * be the following.
 * <ul>
 * <li>Durations
 * <ul>
 * <li><strong>B</strong>, Begin</li>
 * <li><strong>E</strong>, End</li>
 * <li><strong>X</strong>, Complete, this is an event with a duration field</li>
 * <li><strong>i</strong>, Instant / Info</li>
 * </ul>
 * </li>
 * <li>Asynchronous nested messages
 * <ul>
 * <li><strong>b</strong>, nested begin</li>
 * <li><strong>n</strong>, nested info</li>
 * <li><strong>e</strong>, nested end</li>
 * </ul>
 * </li>
 * <li>Flows
 * <ul>
 * <li><strong>s</strong>, flow begin</li>
 * <li><strong>t</strong>, flow step (info)</li>
 * <li><strong>f</strong>, flow end</li>
 * </ul>
 * </li>
 * <li>Object tracking
 * <ul>
 * <li><strong>N</Strong>, Object created</li>
 * <li><strong>D</Strong>, Object destroyed</li>
 * </ul>
 * </li>
 * <li>Mark Events - events that generate markers
 * <ul>
 * <li><strong>R</strong>, Marker event</li>
 * </ul>
 * </ul>
 * <p>
 * To use <strong>durations</strong> and/or <strong>flows</strong>, see
 * {@link ScopeLog} and {@link FlowScopeLog}. These 2 concepts are related.
 * Durations would typically be used to instrument simple methods, while flows
 * would be preferred if there are links to be made with other threads.
 * <p>
 * To use <strong>Asynchronous nested messages</strong>, see
 * {@link #traceAsyncStart(Logger, Level, String, String, int, Object...)}, and
 * {@link #traceAsyncEnd(Logger, Level, String, String, int, Object...)}
 * <p>
 * To use <strong>Object tracking</strong>, see
 * {@link #traceObjectCreation(Logger, Level, Object)} and
 * {@link #traceObjectDestruction(Logger, Level, Object)}
 *
 * The design philosophy of this class is very heavily inspired by the trace
 * event format of Google. The full specification is available <a
 * href=https://docs.google.com/document/d/1CvAClvFfyA5R-PhYUmn5OOQtYMH4h6I0nSsKchNAySU/edit?pli=1#>here</a>.
 * <p>
 *
 * The main goals are clarity of output and simplicity for the developer.
 * Performance is a nice to have, but is not the main concern of this helper. A
 * minor performance impact compared to simply logging the events is to be
 * expected.
 *
 * @author Matthew Khouzam
 * @since 2.2
 * @noinstantiate This class is not intended to be instantiated by clients. It
 *                is a helper class.
 */
public final class TraceCompassLogUtils {

    /*
     * Field names
     */
    private static final String ARGS = "args"; //$NON-NLS-1$
    private static final String NAME = "name"; //$NON-NLS-1$
    private static final String CATEGORY = "cat"; //$NON-NLS-1$
    private static final String ID = "id"; //$NON-NLS-1$
    private static final String TID = "tid"; //$NON-NLS-1$
    private static final String TIMESTAMP = "ts"; //$NON-NLS-1$
    private static final String PHASE = "ph"; //$NON-NLS-1$

    private static final String ARGS_ERROR_MESSAGE = "Data should be in the form of key, value, key1, value1, ... TraceCompassScopeLog was supplied "; //$NON-NLS-1$
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(0);

    private TraceCompassLogUtils() {
        // do nothing
    }

    /**
     * Scope Logger helper. This will automatically log entry and exit of the
     * scope. This scope log will be shown under any scope enclosing it, but
     * will not be the source, or destination of any link to other scopes. If
     * relations should be done with other scopes, the {@link FlowScopeLog}
     * class is more appropriate.
     *
     * Usage:
     *
     * <pre>
     * {@code usage of ScopeLog}
     *  try (ScopeLog linksLogger = new ScopeLog(LOGGER, Level.CONFIG, "Perform Query")) { //$NON-NLS-1$
     *      ss.updateAllReferences();
     *      dataStore.addAll(ss.query(ts, trace));
     *  }
     * </pre>
     * <p>
     * will generate the following trace
     *
     * <pre>
     * {@code trace output}
     *  INFO: {"ts":12345,"ph":"B",tid:1,"name:Perform Query"}
     *  INFO: {"ts":"12366,"ph":"E","tid":1}
     * </pre>
     */
    public static class ScopeLog implements AutoCloseable {

        private final long fThreadId;
        private final Logger fLogger;
        private final Level fLevel;
        private final Map<String, Object> fData = new HashMap<>();

        /**
         * Scope logger constructor
         *
         * @param log
         *            the JUL logger to log to
         * @param level
         *            the log level see {@link Level}
         * @param label
         *            The label of the event pair
         * @param args
         *            Additional messages to pass for this scope, should be in
         *            pairs key, value, key2, value2.... typically arguments.
         *            Note that these arguments will be logged only at the
         *            beginning of the scope
         */
        public ScopeLog(Logger log, Level level, String label, Object... args) {
            long time = System.nanoTime();
            fLogger = log;
            fLevel = level;
            fThreadId = Thread.currentThread().getId();
            fLogger.log(fLevel, (() -> {
                StringBuilder sb = new StringBuilder();
                sb.append('{');
                appendCommon(sb, 'B', time, fThreadId);
                appendName(sb, label);
                appendArgs(sb, args);
                sb.append('}');
                return sb.toString();
            }));
        }

        /**
         * Add a tag to the scope logger, will be written at the exit. This can
         * save space on the trace by having a field appended to an event rather
         * than writing a whole new event for a small chunk of data.
         *
         * If the timing information is important than it would be more
         * appropriate to call
         * {@link TraceCompassLogUtils#traceInstant(Logger, Level, String, Object...)}
         *
         * @param name
         *            the name of the field
         * @param value
         *            The value of the field.
         */
        public void addData(String name, Object value) {
            fData.put(name, value);
        }

        @Override
        public void close() {
            long time = System.nanoTime();
            fLogger.log(fLevel, (() -> {
                StringBuilder sb = new StringBuilder();
                sb.append('{');
                appendCommon(sb, 'E', time, fThreadId);
                return appendArgs(sb, fData).append('}').toString();
            }));
        }
    }

    /**
     * Builder class for the {@link FlowScopeLog}. One can either set a category
     * or a parent scope before building the flow scope log. If none is set, a
     * default category called "null" will be used.
     *
     * @author Geneviève Bastien
     */
    public static class FlowScopeLogBuilder {

        private final Logger fLogger;
        private final Level fLevel;
        private final String fLabel;
        private final Object[] fArgs;
        private @Nullable String fCategory = null;
        private @Nullable FlowScopeLog fParent = null;

        /**
         * Flow scope log builder constructor
         *
         * @param logger
         *            the JUL logger
         * @param level
         *            the log level see {@link Level}
         * @param label
         *            The label of the event pair
         * @param args
         *            the messages to pass, should be in pairs key, value, key2,
         *            value2.... typically arguments
         */
        public FlowScopeLogBuilder(Logger logger, Level level, String label, Object... args) {
            fLogger = logger;
            fLevel = level;
            fLabel = label;
            fArgs = args;
        }

        /**
         * Set a category for the flow scope. When building the scope, an ID
         * will be automatically generated.
         *
         * This method is mutually exclusive with
         * {@link #setParentScope(FlowScopeLog)}. Calling both will throw an
         * exception.
         *
         * @param category
         *            The category of this flow
         * @return This builder
         */
        public FlowScopeLogBuilder setCategory(String category) {
            if (fParent != null) {
                throw new IllegalStateException("FlowScopeLogBuilder: Cannot set a category if a parent has already been set"); //$NON-NLS-1$
            }
            fCategory = category;
            return this;
        }

        /**
         * Set a parent scope for the flow scope to build. The scope will have
         * the same category and ID as the parent scope.
         *
         * This method is mutually exclusive with {@link #setCategory(String)}.
         * Calling both will throw an exception.
         *
         * @param parent
         *            The parent scope
         * @return This builder
         */
        public FlowScopeLogBuilder setParentScope(FlowScopeLog parent) {
            if (fCategory != null) {
                throw new IllegalStateException("FlowScopeLogBuilder: Cannot set a parent scope if a category has already been set"); //$NON-NLS-1$
            }
            fParent = parent;
            return this;
        }

        /**
         * Build the flow scope log
         *
         * @return The flow scope log
         */
        public FlowScopeLog build() {
            FlowScopeLog parent = fParent;
            if (parent != null) {
                return new FlowScopeLog(fLogger, fLevel, fLabel, parent.fCategory, parent.fId, fArgs);
            }
            return new FlowScopeLog(fLogger, fLevel, fLabel, String.valueOf(fCategory), ID_GENERATOR.incrementAndGet(), fArgs);
        }

    }

    /**
     * Flow Scope Logger helper. It will automatically log entry and exit of the
     * scope. It can be used with other flow scopes to follow the program flow
     * across threads. To do so, these scopes save more data, so take more disk
     * space. If there is no inter-process/thread communication to follow, the
     * {@link ScopeLog} class would be more appropriate.
     *
     * Usage: this can be used to track asynchronous threads communication. This
     * can be used in scatter-gather/map-reduce operations as well as threads
     * that trigger a UI Thread operation.
     *
     * <pre>
     * {@code usage of FlowScopeLog}
     *  try (FlowScopeLog linksLogger = new FlowScopeLog(LOGGER, Level.CONFIG, "Perform Query", "category", 0x100)) { //$NON-NLS-1$
     *      Display.asynchExec(()->{
     *      try(FlowScopeLog linksLogger2 = new FlowScopeLog(LOGGER, Level.CONFIG, "Update UI", "category", linksLogger.getId()) {
     *          linksLogger.step("updating ui");
     *      };
     *      linksLogger.step("forked thread");
     *  }
     * </pre>
     * <p>
     * will generate the following trace (order not guaranteed)
     *
     * <pre>
     * {@code trace output}
     *  INFO: {"ts":12345,"ph":"s",tid:1,"name":"Perform Query", "cat":"category", "id":256}
     *  INFO: {"ts":12346","ph":"t",tid:1,"name":"forked thread","cat":"category", "id":256}
     *  INFO: {"ts":"12366,"ph":"f","tid":1,"cat":"category", "id":256}
     *  INFO: {"ts":12400,"ph":"s",tid:0,"name":"Update UI","cat":"category", "id":256}
     *  INFO: {"ts":12416","ph":"t",tid:0,"name":"updating ui", "cat":"category", "id":256}
     *  INFO: {"ts":"12420,"ph":"f","tid":0,"cat":"category", "id":256}
     * </pre>
     */
    public static class FlowScopeLog implements AutoCloseable {

        private final long fThreadId;
        private final Logger fLogger;
        private final Level fLevel;
        private final int fId;
        private final String fCategory;
        private final Map<String, Object> fData = new HashMap<>();

        /**
         * Flow scope logger constructor
         *
         * @param log
         *            the JUL logger
         * @param level
         *            the log level see {@link Level}
         * @param label
         *            The label of the event pair
         * @param category
         *            the category of the flow events
         * @param id
         *            The id of the flow
         * @param args
         *            the messages to pass, should be in pairs key, value, key2,
         *            value2.... typically arguments
         */
        private FlowScopeLog(Logger log, Level level, String label, String category, int id, Object... args) {
            long time = System.nanoTime();
            fId = id;
            fLogger = log;
            fLevel = level;
            fCategory = category;
            fThreadId = Thread.currentThread().getId();
            fLogger.log(fLevel, (() -> {
                StringBuilder sb = new StringBuilder();
                sb.append('{');
                appendCommon(sb, 's', time, fThreadId);
                appendName(sb, label);
                appendCategory(sb, category);
                appendId(sb, fId);
                appendArgs(sb, args);
                sb.append('}');
                return sb.toString();
            }));
        }

        /**
         * Flow step, could be launching a new process or simply hit a
         * checkpoint
         *
         * @param label
         *            The label for this step
         * @param args
         *            the arguments to log
         */
        public void step(String label, Object... args) {
            long time = System.nanoTime();
            fLogger.log(fLevel, (() -> {
                StringBuilder sb = new StringBuilder();
                sb.append('{');
                appendCommon(sb, 't', time, fThreadId);
                appendName(sb, label);
                appendCategory(sb, fCategory);
                appendId(sb, fId);
                appendArgs(sb, args);
                sb.append('}');
                return sb.toString();
            }));
        }

        /**
         * Add a tag to the scope logger, will be written at the exit. This can
         * save space on the trace by having a field appended to an event rather
         * than writing a whole new event for a small chunk of data.
         *
         *
         * If the timing information is important, then it would be more
         * appropriate to call {@link #step(String, Object...)}
         *
         * @param name
         *            the name of the field
         * @param value
         *            The value of the field.
         */
        public void addData(String name, Object value) {
            fData.put(name, value);
        }

        @Override
        public void close() {
            long time = System.nanoTime();
            fLogger.log(fLevel, (() -> {
                StringBuilder sb = new StringBuilder();
                sb.append('{');
                appendCommon(sb, 'f', time, fThreadId);
                appendCategory(sb, fCategory);
                appendId(sb, fId);
                appendArgs(sb, fData);
                sb.append('}');
                return sb.toString();
            }));
        }
    }

    /**
     * Trace Object Creation, logs the beginning of an object's life cycle.
     * Typically one can put this in the object's constructor. However if an
     * object is mutable, it can be tracked through phases with this method,
     * then the object can be re-used, however, the resulting analyses may yield
     * erroneous data if precautions are not taken.
     *
     * For mutable objects, save the return value of the call. This will be passed
     * to the destruction of the object and then it can be matched.
     *
     * @param logger
     *            The JUL logger
     * @param level
     *            The {@link Level} of this event.
     * @param item
     *            the Object to trace
     * @return The unique ID of this object (there may be collisions)
     */
    public static int traceObjectCreation(Logger logger, Level level, Object item) {
        long time = System.nanoTime();
        long threadId = Thread.currentThread().getId();
        int identityHashCode = System.identityHashCode(item);
        logger.log(level, () -> {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            appendCommon(sb, 'N', time, threadId);
            appendName(sb, item.getClass().getSimpleName());
            appendId(sb, identityHashCode);
            return sb.append('}').toString();
        });
        return identityHashCode;
    }

    /**
     * Trace Object Destruction, logs the end of an object's life cycle.
     * Typically one can put this in the object's Dispose(). However if an
     * object is mutable, it can be tracked through phases with this method,
     * then the object can be re-used, however, the resulting analyses may yield
     * erroneous data if precautions are not taken.
     *
     * @param logger
     *            The JUL logger
     * @param level
     *            The {@link Level} of this event.
     * @param item
     *            the Object to trace
     */
    public static void traceObjectDestruction(Logger logger, Level level, Object item) {
        long time = System.nanoTime();
        long threadId = Thread.currentThread().getId();
        logger.log(level, () -> {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            appendCommon(sb, 'D', time, threadId);
            appendName(sb, item.getClass().getSimpleName());
            appendId(sb, System.identityHashCode(item));
            return sb.append('}').toString();
        });
    }

    /**
     * Trace Object Destruction, logs the end of an object's life cycle.
     * Typically one can put this in the object's Dispose(). However if an
     * object is mutable, it can be tracked through phases with this method,
     * then the object can be re-used, however, the resulting analyses may be
     *
     * @param logger
     *            The JUL logger
     * @param level
     *            The {@link Level} of this event.
     * @param item
     *            the Object to trace
     * @param uniqueId
     *            The unique ID
     */
    public static void traceObjectDestruction(Logger logger, Level level, Object item, int uniqueId) {
        long time = System.nanoTime();
        long threadId = Thread.currentThread().getId();
        logger.log(level, () -> {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            appendCommon(sb, 'D', time, threadId);
            appendName(sb, item.getClass().getSimpleName());
            appendId(sb, uniqueId);
            return sb.append('}').toString();
        });
    }

    /**
     * Asynchronous events are used to specify asynchronous operations, such as
     * an asynchronous (or synchronous) draw, or a network operation. Call this
     * method at the beginning of such an operation.
     *
     * @param logger
     *            The JUL logger
     * @param level
     *            The {@link Level} of this event.
     * @param name
     *            The name of the asynchronous message
     * @param category
     *            the category of the asynchronous event
     * @param id
     *            The unique ID of a transaction
     * @param args
     *            Additional arguments to log
     */
    public static void traceAsyncStart(Logger logger, Level level, @Nullable String name, @Nullable String category, int id, Object... args) {
        long time = System.nanoTime();
        long threadId = Thread.currentThread().getId();
        logger.log(level, () -> {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            appendCommon(sb, 'b', time, threadId);
            appendName(sb, name);
            appendCategory(sb, category);
            appendId(sb, id);
            return appendArgs(sb, args).append('}').toString();
        });
    }

    /**
     * Asynchronous events are used to specify asynchronous operations, such as
     * an asynchronous (or synchronous) draw, or a network operation. Call this
     * method to augment the asynchronous event with nested information.
     *
     * @param logger
     *            The JUL logger
     * @param level
     *            The {@link Level} of this event.
     * @param name
     *            The name of the asynchronous message
     * @param category
     *            the category of the asynchronous event
     * @param id
     *            The unique ID of a transaction
     * @param args
     *            Additional arguments to log
     */
    public static void traceAsyncNested(Logger logger, Level level, @Nullable String name, @Nullable String category, int id, Object... args) {
        long time = System.nanoTime();
        long threadId = Thread.currentThread().getId();
        logger.log(level, () -> {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            appendCommon(sb, 'n', time, threadId);
            appendName(sb, name);
            appendCategory(sb, category);
            appendId(sb, id);
            return appendArgs(sb, args).append('}').toString();
        });
    }

    /**
     * Asynchronous events are used to specify asynchronous operations, such as
     * an asynchronous (or synchronous) draw, or a network operation. Call this
     * method at the end of such an operation.
     *
     * @param logger
     *            The JUL logger
     * @param level
     *            The {@link Level} of this event.
     * @param name
     *            The name of the asynchronous message
     * @param category
     *            the category of the asynchronous event
     * @param id
     *            The unique ID of a transaction
     * @param args
     *            Additional arguments to log
     */
    public static void traceAsyncEnd(Logger logger, Level level, @Nullable String name, @Nullable String category, int id, Object... args) {
        long time = System.nanoTime();
        long threadId = Thread.currentThread().getId();
        logger.log(level, () -> {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            appendCommon(sb, 'e', time, threadId);
            appendName(sb, name);
            appendCategory(sb, category);
            appendId(sb, id);
            return appendArgs(sb, args).append('}').toString();
        });
    }

    /**
     * Instant events, created to indicate an item of interest has occurred,
     * similar to a standard System.out.println() or a
     * Java.util.Logger#log(Level). This one provides an event in a more
     * structured way. This should be the method to call to save data that
     * should have a zero duration, as it will ensure a log format that can then
     * be parsed by a trace type.
     *
     * @param logger
     *            The JUL logger
     * @param level
     *            The {@link Level} of this event.
     * @param name
     *            The name of the asynchronous message
     * @param args
     *            Additional arguments to log
     */
    public static void traceInstant(Logger logger, Level level, String name, Object... args) {
        long time = System.nanoTime();
        long threadId = Thread.currentThread().getId();
        logger.log(level, () -> {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            appendCommon(sb, 'i', time, threadId);
            appendName(sb, name);
            return appendArgs(sb, args).append('}').toString();
        });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /*
     * USE ME FIRST
     */
    private static StringBuilder appendCommon(StringBuilder appendTo, char phase, long time, long threadId) {
        writeObject(appendTo, TIMESTAMP, time).append(','); // $NON-NLS-1$
        writeObject(appendTo, PHASE, phase).append(',');
        return writeObject(appendTo, TID, threadId); // $NON-NLS-1$
    }

    private static StringBuilder appendName(StringBuilder sb, @Nullable String name) {
        if (name != null) {
            sb.append(',');
            writeObject(sb, NAME, name);
        }
        return sb;
    }

    private static StringBuilder appendCategory(StringBuilder sb, @Nullable String category) {
        if (category != null) {
            sb.append(',');
            writeObject(sb, CATEGORY, category);
        }
        return sb;
    }

    private static StringBuilder appendId(StringBuilder sb, int id) {
        return sb.append(',')
                .append('"')
                .append(ID)
                .append("\":\"0x") //$NON-NLS-1$
                .append(Integer.toHexString(id))
                .append('"');
    }

    private static StringBuilder appendArgs(StringBuilder sb, Map<String, Object> args) {
        if (!args.isEmpty()) {
            sb.append(',')
                    .append('"')
                    .append(ARGS)
                    .append('"')
                    .append(':');
            Object[] argsArray = new Object[2 * args.size()];
            Iterator<Entry<String, Object>> entryIter = args.entrySet().iterator();
            for (int i = 0; i < args.size(); i++) {
                Entry<String, Object> entry = entryIter.next();
                argsArray[i] = entry.getKey();
                argsArray[i + 1] = entry.getValue();
            }
            getArgs(sb, argsArray);
        }
        return sb;
    }

    private static StringBuilder appendArgs(StringBuilder sb, Object... args) {
        if (args.length > 0) {
            sb.append(',')
                    .append('"')
                    .append(ARGS)
                    .append('"')
                    .append(':');
            getArgs(sb, args);
        }
        return sb;
    }

    private static StringBuilder getArgs(StringBuilder appendTo, Object[] data) {
        if (data.length == 0) {
            return appendTo;
        }
        Set<String> tester = new HashSet<>();
        appendTo.append('{');
        if (data.length == 1) {
            // not in contract, but let's assume here that people are still new
            // at this
            appendTo.append("\"msg\":\"").append(data[0]).append('"'); //$NON-NLS-1$
        } else {
            if (data.length % 2 != 0) {
                throw new IllegalArgumentException(
                        ARGS_ERROR_MESSAGE + "an odd number of messages" + Arrays.asList(data).toString()); //$NON-NLS-1$
            }
            for (int i = 0; i < data.length - 1; i += 2) {
                Object value = data[i + 1];
                String keyVal = String.valueOf(data[i]);
                if (tester.contains(keyVal)) {
                    throw new IllegalArgumentException(ARGS_ERROR_MESSAGE + "an duplicate field names : " + keyVal); //$NON-NLS-1$
                }
                tester.add(keyVal);
                if (i > 0) {
                    appendTo.append(',');
                }
                writeObject(appendTo, keyVal, NonNullUtils.checkNotNull(value));
            }
        }

        return appendTo.append('}');
    }

    private static StringBuilder writeObject(StringBuilder appendTo, Object key, Object value) {
        appendTo.append('"').append(key).append('"').append(':');
        if (value instanceof Number) {
            appendTo.append(value);
        } else {
            appendTo.append('"').append(value).append('"');
        }
        return appendTo;
    }

}