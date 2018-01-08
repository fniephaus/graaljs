/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.runtime.builtins;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.HiddenKey;
import com.oracle.truffle.api.object.Property;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSRealm;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.JSTruffleOptions;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.JSObjectUtil;
import com.oracle.truffle.js.runtime.objects.JSShape;

public final class JSDate extends JSBuiltinObject implements JSConstructorFactory.Default.WithFunctions {

    public static final String CLASS_NAME = "Date";
    public static final String PROTOTYPE_NAME = "Date.prototype";

    private static DateTimeFormatter jsDateFormat;
    private static DateTimeFormatter jsDateFormatBeforeYear0;
    private static DateTimeFormatter jsDateFormatAfterYear9999;
    private static DateTimeFormatter jsDateFormatISO;
    private static DateTimeFormatter jsShortDateFormat;
    private static DateTimeFormatter jsShortDateLocalFormat;
    private static DateTimeFormatter jsShortTimeFormat;
    private static DateTimeFormatter jsShortTimeLocalFormat;
    private static DateTimeFormatter jsDateToStringFormat;
    private static final JSDate INSTANCE = new JSDate();

    private static final HiddenKey TIME_MILLIS_ID = new HiddenKey("timeMillis");
    private static final Property TIME_MILLIS_PROPERTY;

    private static final int HOURS_PER_DAY = 24;
    private static final int MINUTES_PER_HOUR = 60;
    private static final int SECONDS_PER_MINUTE = 60;
    private static final int MS_PER_SECOND = 1000;
    public static final int MS_PER_MINUTE = 60000;
    private static final int MS_PER_HOUR = 3600000;
    private static final int MS_PER_DAY = 3600000 * 24;
    public static final double MAX_DATE = 8.64E15;

    public static final String INVALID_DATE_STRING = "Invalid Date";

    static {
        Shape.Allocator allocator = JSShape.makeAllocator(JSObject.LAYOUT);
        TIME_MILLIS_PROPERTY = JSObjectUtil.makeHiddenProperty(TIME_MILLIS_ID, allocator.locationForType(double.class));
    }

    private JSDate() {
    }

    public static void setTimeMillisField(DynamicObject obj, double timeMillis) {
        assert isJSDate(obj);
        TIME_MILLIS_PROPERTY.setSafe(obj, timeMillis, null);
    }

    public static double getTimeMillisField(DynamicObject obj) {
        assert isJSDate(obj);
        return (double) TIME_MILLIS_PROPERTY.get(obj, isJSDate(obj));
    }

    public static boolean isJSDate(Object obj) {
        return JSObject.isDynamicObject(obj) && isJSDate((DynamicObject) obj);
    }

    public static boolean isJSDate(DynamicObject obj) {
        return isInstance(obj, INSTANCE);
    }

    @Override
    public String getClassName() {
        return CLASS_NAME;
    }

    @Override
    public String getClassName(DynamicObject object) {
        return getClassName();
    }

    @Override
    public String getBuiltinToStringTag(DynamicObject object) {
        return getClassName(object);
    }

    @Override
    public DynamicObject createPrototype(JSRealm realm, DynamicObject ctor) {
        JSContext ctx = realm.getContext();
        DynamicObject datePrototype = JSObject.create(realm, realm.getObjectPrototype(), ctx.getEcmaScriptVersion() < 6 ? INSTANCE : JSUserObject.INSTANCE);
        if (ctx.getEcmaScriptVersion() < 6) {
            JSObjectUtil.putHiddenProperty(datePrototype, TIME_MILLIS_PROPERTY, Double.NaN);
        }
        JSObjectUtil.putConstructorProperty(ctx, datePrototype, ctor);
        JSObjectUtil.putFunctionsFromContainer(realm, datePrototype, PROTOTYPE_NAME);
        return datePrototype;
    }

    public static Shape makeInitialShape(JSContext ctx, DynamicObject prototype) {
        assert JSShape.getProtoChildTree(prototype.getShape(), INSTANCE) == null;
        Shape initialShape = JSObjectUtil.getProtoChildShape(prototype, INSTANCE, ctx);
        initialShape = initialShape.addProperty(TIME_MILLIS_PROPERTY);
        return initialShape;
    }

    public static JSConstructor createConstructor(JSRealm realm) {
        return INSTANCE.createConstructorAndPrototype(realm);
    }

    @TruffleBoundary
    public static double executeConstructor(double[] argsEvaluated, boolean inputIsUTC, JSContext context) {
        double year = argsEvaluated.length > 0 ? argsEvaluated[0] : Double.NaN;
        double month = argsEvaluated.length > 1 ? argsEvaluated[1] : 0;

        if (Double.isNaN(year) || Double.isInfinite(year) || Double.isNaN(month) || Double.isInfinite(month)) {
            return Double.NaN;
        }

        double day = getArgOrDefault(argsEvaluated, 2, 1);
        double hour = getArgOrDefault(argsEvaluated, 3, 0);
        double minute = getArgOrDefault(argsEvaluated, 4, 0);
        double second = getArgOrDefault(argsEvaluated, 5, 0);
        double ms = getArgOrDefault(argsEvaluated, 6, 0);

        return makeDate(toFullYear(year), month, day, hour, minute, second, ms, inputIsUTC ? 0 : null, context);
    }

    private static double getArgOrDefault(double[] argsEvaluated, int index, int def) {
        if (argsEvaluated.length > index) {
            return argsEvaluated[index];
        }
        return def;
    }

    // 15.9.1.2
    private static double day(double t) {
        return floor(t / MS_PER_DAY);
    }

    // 15.9.1.2
    private static double timeWithinDay(double t) {
        return secureNegativeModulo(t, MS_PER_DAY);
    }

    private static double daysInYear(double y) {
        if (y % 4 != 0) {
            return 365;
        }
        if (y % 100 != 0) {
            return 366;
        }
        if (y % 400 != 0) {
            return 365;
        }
        return 366;
    }

    private static double dayFromYear(double y) {
        return 365 * (y - 1970) + floor((y - 1969) / 4) - floor((y - 1901) / 100) + floor((y - 1601) / 400);
    }

    private static double timeFromYear(double y) {
        return MS_PER_DAY * dayFromYear(y);
    }

    @TruffleBoundary
    public static double yearFromTime(double t) {
        // the largest integer y (closest to positive infinity) such that TimeFromYear(y) <= t
        if (Double.isNaN(t)) {
            return Double.NaN;
        }
        double y = JSRuntime.mathFloor(t / MS_PER_DAY / 365) + 1970;
        y += floor((t - timeFromYear(y)) / MS_PER_DAY / 365); // correct leap year errors

        if (timeFromYear(y) <= t) {
            y++;
            if (timeFromYear(y) <= t) {
                y++;
            }
        }
        assert timeFromYear(y - 1) <= t && t <= timeFromYear(y);
        return y - 1;
    }

    private static double inLeapYear(double t) {
        if (daysInYear(yearFromTime(t)) == 365) {
            return 0;
        }
        return 1;
    }

    // 15.9.1.4
    @TruffleBoundary
    public static double monthFromTime(double t) {
        if (Double.isNaN(t)) {
            return Double.NaN;
        }
        double leap = inLeapYear(t);
        double day = dayWithinYear(t);

        assert (0 <= day) && (day < (365 + leap)) : "should not reach here";

        if (day < 31) {
            return 0;
        }
        if (day < (59 + leap)) {
            return 1;
        }
        if (day < (90 + leap)) {
            return 2;
        }
        if (day < (120 + leap)) {
            return 3;
        }
        if (day < (151 + leap)) {
            return 4;
        }
        if (day < (181 + leap)) {
            return 5;
        }
        if (day < (212 + leap)) {
            return 6;
        }
        if (day < (243 + leap)) {
            return 7;
        }
        if (day < (273 + leap)) {
            return 8;
        }
        if (day < (304 + leap)) {
            return 9;
        }
        if (day < (334 + leap)) {
            return 10;
        }
        return 11;
    }

    // 15.9.1.4
    private static double dayWithinYear(double t) {
        return day(t) - dayFromYear(yearFromTime(t));
    }

    // 15.9.1.5
    @TruffleBoundary
    public static double dateFromTime(double t) {
        double leap = inLeapYear(t);
        double day = dayWithinYear(t);
        double dayMinusLeap = day - leap;
        switch ((int) monthFromTime(t)) {
            case 0:
                return day + 1;
            case 1:
                return day - 30;
            case 2:
                return dayMinusLeap - 58;
            case 3:
                return dayMinusLeap - 89;
            case 4:
                return dayMinusLeap - 119;
            case 5:
                return dayMinusLeap - 150;
            case 6:
                return dayMinusLeap - 180;
            case 7:
                return dayMinusLeap - 211;
            case 8:
                return dayMinusLeap - 242;
            case 9:
                return dayMinusLeap - 272;
            case 10:
                return dayMinusLeap - 303;
            case 11:
                return dayMinusLeap - 333;
        }
        assert false : "should not reach here";
        return Double.NaN;
    }

    // 15.9.1.6
    @TruffleBoundary
    public static double weekDay(double t) {
        int result = ((int) day(t) + 4) % 7; // cast to int to avoid -0.0
        return result >= 0 ? result : result + 7;
    }

    /**
     * ES5 15.9.1.8 Daylight Saving Time Adjustment, in milliseconds.
     */
    @TruffleBoundary
    private static double daylightSavingTA(ZoneId zone, double t) {
        Duration d = zone.getRules().getDaylightSavings(Instant.ofEpochMilli((long) t));
        return d.getSeconds() * 1000L;
    }

    // 15.9.1.9
    @TruffleBoundary
    public static double localTime(double t, JSContext context) {
        long localTZA = context.getLocalTZA();
        return t + localTZA + daylightSavingTA(context.getLocalTimeZoneId(), t);
    }

    private static double utc(double t, JSContext context) {
        long localTZA = context.getLocalTZA();
        return t - localTZA - daylightSavingTA(context.getLocalTimeZoneId(), t - localTZA);
    }

    // 15.9.1.10
    @TruffleBoundary
    public static double hourFromTime(double t) {
        return (int) secureNegativeModulo(floor(t / MS_PER_HOUR), HOURS_PER_DAY);
    }

    @TruffleBoundary
    public static double minFromTime(double t) {
        return (int) secureNegativeModulo(floor(t / MS_PER_MINUTE), MINUTES_PER_HOUR);
    }

    @TruffleBoundary
    public static double secFromTime(double t) {
        return (int) secureNegativeModulo(floor(t / MS_PER_SECOND), SECONDS_PER_MINUTE);
    }

    @TruffleBoundary
    public static double msFromTime(double t) {
        return (long) secureNegativeModulo(t, MS_PER_SECOND);
    }

    private static double secureNegativeModulo(double value, double modulo) {
        double result = value % modulo;
        if (result >= 0) {
            return result;
        } else {
            return result + modulo;
        }
    }

    // 15.9.1.11
    @TruffleBoundary
    private static double makeTime(double hour, double min, double sec, double ms) {
        if (!isFinite(hour) || !isFinite(min) || !isFinite(sec) || !isFinite(ms)) {
            return Double.NaN;
        }
        long h = doubleToLong(hour);
        long m = doubleToLong(min);
        long s = doubleToLong(sec);
        long milli = doubleToLong(ms);
        return h * MS_PER_HOUR + m * MS_PER_MINUTE + s * MS_PER_SECOND + milli;
    }

    // 15.9.1.12
    @TruffleBoundary
    private static double makeDay(double year, double month, double date) {
        if (!isFinite(year) || !isFinite(month) || !isFinite(date)) {
            return Double.NaN;
        }
        double y = doubleToLong(year);
        double m = doubleToLong(month);
        double dt = doubleToLong(date);

        double ym = y + floor(m / 12);
        int mn = (int) (m % 12);
        if (mn < 0) {
            mn += 12;
        }

        if (ym < Year.MIN_VALUE || ym > Year.MAX_VALUE) {
            return Double.NaN;
        }

        double t = LocalDate.of((int) ym, mn + 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        return day(t) + dt - 1;
    }

    private static long doubleToLong(double value) {
        if (Double.isNaN(value)) {
            return 0;
        }
        return (long) value;
    }

    // 15.9.1.13
    @TruffleBoundary
    private static double makeDate(double day, double time) {
        if (!isFinite(day) || !isFinite(time)) {
            return Double.NaN;
        }
        return (day * MS_PER_DAY + time);
    }

    @TruffleBoundary
    public static double makeDate(double y, double m, double d, double h, double min, double sec, double ms, Integer timezone, JSContext context) {
        double day = makeDay(y, m, d);
        double time = makeTime(h, min, sec, ms);
        double date = makeDate(day, time);

        if (timezone == null) {
            date = utc(date, context);
        } else {
            date -= timezone * 60000;
        }
        return timeClip(date);
    }

    /**
     * Implementation of ECMAScript 5.1 15.9.1.14 TimeClip.
     */
    @TruffleBoundary
    public static double timeClip(double time) {
        if (Double.isInfinite(time) || Double.isNaN(time)) {
            return Double.NaN;
        }
        if (Math.abs(time) > MAX_DATE) {
            return Double.NaN;
        }
        // The standard expects only integer values, cf. 15.9.1.1
        // it does not state, however, WHERE the conversion should happen
        return JSRuntime.toInteger(time);
    }

    // helper function
    private static boolean isFinite(double d) {
        return !(Double.isNaN(d) || Double.isInfinite(d));
    }

    // helper function
    private static double floor(double d) {
        return JSRuntime.mathFloor(d);
    }

    public static DynamicObject create(JSContext context, double timeMillis) {
        DynamicObject obj = JSObject.create(context, context.getDateFactory(), timeMillis);
        assert isJSDate(obj);
        return obj;
    }

    public static double valueOf(DynamicObject thisObj) {
        return getTimeMillisField(thisObj);
    }

    public static double setTime(DynamicObject thisDate, double time) {
        double v = timeClip(time);
        setTimeMillisField(thisDate, v);
        return v;
    }

    public static double setMilliseconds(DynamicObject thisDate, double ms, boolean isUTC, JSContext context) {
        double t = localTime(getTimeMillisField(thisDate), isUTC, context);
        double time = makeTime(hourFromTime(t), minFromTime(t), secFromTime(t), ms);
        double u = timeClip(utc(makeDate(day(t), time), isUTC, context));
        setTimeMillisField(thisDate, u);
        return u;
    }

    public static double setSeconds(DynamicObject thisDate, double s, double ms, boolean msSpecified, boolean isUTC, JSContext context) {
        double t = localTime(getTimeMillisField(thisDate), isUTC, context);
        double milli = msSpecified ? ms : msFromTime(t);
        double date = makeDate(day(t), makeTime(hourFromTime(t), minFromTime(t), s, milli));
        double u = timeClip(utc(date, isUTC, context));
        setTimeMillisField(thisDate, u);
        return u;
    }

    public static double setMinutes(DynamicObject thisDate, double m, double s, boolean sSpecified, double ms, boolean msSpecified, boolean isUTC, JSContext context) {
        double t = localTime(getTimeMillisField(thisDate), isUTC, context);
        double milli = msSpecified ? ms : msFromTime(t);
        double sec = sSpecified ? s : secFromTime(t);
        double date = makeDate(day(t), makeTime(hourFromTime(t), m, sec, milli));
        double u = timeClip(utc(date, isUTC, context));
        setTimeMillisField(thisDate, u);
        return u;
    }

    public static double setHours(DynamicObject thisDate, double h, double m, boolean mSpecified, double s, boolean sSpecified, double ms, boolean msSpecified, boolean isUTC, JSContext context) {
        double t = localTime(getTimeMillisField(thisDate), isUTC, context);
        double milli = msSpecified ? ms : msFromTime(t);
        double sec = sSpecified ? s : secFromTime(t);
        double min = mSpecified ? m : minFromTime(t);
        double date = makeDate(day(t), makeTime(h, min, sec, milli));
        double u = timeClip(utc(date, isUTC, context));
        setTimeMillisField(thisDate, u);
        return u;
    }

    public static double setDate(DynamicObject thisDate, double date, boolean isUTC, JSContext context) {
        double t = localTime(getTimeMillisField(thisDate), isUTC, context);
        double newDate = makeDate(makeDay(yearFromTime(t), monthFromTime(t), date), timeWithinDay(t));
        double u = timeClip(utc(newDate, isUTC, context));
        setTimeMillisField(thisDate, u);
        return u;
    }

    public static double setMonth(DynamicObject thisDate, double month, double date, boolean dateSpecified, boolean isUTC, JSContext context) {
        double t = localTime(getTimeMillisField(thisDate), isUTC, context);
        double dt = dateSpecified ? date : dateFromTime(t);
        double newDate = utc(makeDate(makeDay(yearFromTime(t), month, dt), timeWithinDay(t)), isUTC, context);
        double u = timeClip(newDate);
        setTimeMillisField(thisDate, u);
        return u;
    }

    public static double setFullYear(DynamicObject thisDate, double year, double month, boolean monthSpecified, double date, boolean dateSpecified, boolean isUTC, JSContext context) {
        double timeFieldValue = getTimeMillisField(thisDate);
        double t = Double.isNaN(timeFieldValue) ? 0 : localTime(timeFieldValue, isUTC, context);
        double dt = dateSpecified ? date : dateFromTime(t);
        double m = monthSpecified ? month : monthFromTime(t);
        double newDate = makeDate(makeDay(year, m, dt), timeWithinDay(t));
        double u = timeClip(utc(newDate, isUTC, context));
        setTimeMillisField(thisDate, u);
        return u;
    }

    public static double setYear(DynamicObject thisDate, double year, JSContext context) {
        double t = getTimeMillisField(thisDate);
        t = Double.isNaN(t) ? 0 : localTime(t, context); // cf. B.2.5, clause 1
        if (Double.isNaN(year)) {
            setTimeMillisField(thisDate, Double.NaN);
            return Double.NaN;
        }
        double fullYear = toFullYear(year);
        double r5 = makeDay(fullYear, monthFromTime(t), dateFromTime(t));
        double r6 = timeClip(utc(makeDate(r5, timeWithinDay(t)), context));
        setTimeMillisField(thisDate, r6);
        return r6;
    }

    private static double toFullYear(double year) {
        // 0 <= ToInteger(year) <= 99 according to standard, but we are omitting the ToInteger here!
        if (-1 < year && year < 100) {
            return 1900 + (int) year;
        }
        return year;
    }

    @TruffleBoundary
    public static String formatLocal(DateTimeFormatter format, double time, JSContext context) {
        return Instant.ofEpochMilli((long) time).atZone(context.getLocalTimeZoneId()).format(format);
    }

    @TruffleBoundary
    public static String formatUTC(DateTimeFormatter format, double time) {
        return Instant.ofEpochMilli((long) time).atZone(ZoneOffset.UTC).format(format);
    }

    @TruffleBoundary
    public static String toString(double time, JSContext context) {
        if (Double.isNaN(time)) {
            return INVALID_DATE_STRING;
        }
        return formatLocal(getDateToStringFormat(), time, context);
    }

    @TruffleBoundary
    public static String toISOStringIntl(double time) {
        return formatUTC(getJSDateFormat(time), time);
    }

    public static boolean isTimeValid(double time) {
        return !(Double.isNaN(time) || Double.isInfinite(time));
    }

    private static double localTime(double time, boolean isUTC, JSContext context) {
        return isUTC ? time : localTime(time, context);
    }

    private static double utc(double time, boolean isUTC, JSContext context) {
        return isUTC ? time : utc(time, context);
    }

    public static DateTimeFormatter getJSDateFormat(double time) {
        long milliseconds = (long) time;
        if (milliseconds < -62167219200000L) {
            if (jsDateFormatBeforeYear0 == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                jsDateFormatBeforeYear0 = DateTimeFormatter.ofPattern("uuuuuu-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            }
            return jsDateFormatBeforeYear0;
        } else if (milliseconds >= 253402300800000L) {
            if (jsDateFormatAfterYear9999 == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                jsDateFormatAfterYear9999 = DateTimeFormatter.ofPattern("+uuuuuu-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            }
            return jsDateFormatAfterYear9999;
        } else {
            if (jsDateFormat == null) {
                // UTC
                CompilerDirectives.transferToInterpreterAndInvalidate();
                jsDateFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            }
            return jsDateFormat;
        }
    }

    public static DateTimeFormatter getJSDateUTCFormat() {
        if (jsDateFormatISO == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            jsDateFormatISO = DateTimeFormatter.ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'", Locale.US);
        }
        return jsDateFormatISO;
    }

    public static DateTimeFormatter getJSShortDateFormat() {
        if (jsShortDateFormat == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            // no UTC
            jsShortDateFormat = DateTimeFormatter.ofPattern("EEE MMM dd uuuu", Locale.US);
        }
        return jsShortDateFormat;
    }

    public static DateTimeFormatter getJSShortDateLocalFormat() {
        if (jsShortDateLocalFormat == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            // no UTC
            jsShortDateLocalFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.US);
        }
        return jsShortDateLocalFormat;
    }

    public static DateTimeFormatter getJSShortTimeFormat() {
        if (jsShortTimeFormat == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            // no UTC
            jsShortTimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss 'GMT'Z (z)", Locale.US);
        }
        return jsShortTimeFormat;
    }

    public static DateTimeFormatter getJSShortTimeLocalFormat() {
        if (jsShortTimeLocalFormat == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            // no UTC
            jsShortTimeLocalFormat = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault(Locale.Category.FORMAT));
        }
        return jsShortTimeLocalFormat;
    }

    public static DateTimeFormatter getDateToStringFormat() {
        if (jsDateToStringFormat == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            jsDateToStringFormat = DateTimeFormatter.ofPattern("EEE MMM dd uuuu HH:mm:ss 'GMT'Z (z)", Locale.US);
        }
        return jsDateToStringFormat;
    }

    @TruffleBoundary
    @Override
    public String safeToString(DynamicObject obj) {
        double time = getTimeMillisField(obj);
        String formattedDate;
        if (isTimeValid(time)) {
            formattedDate = toISOStringIntl(time);
        } else {
            formattedDate = INVALID_DATE_STRING;
        }
        if (JSTruffleOptions.NashornCompatibilityMode) {
            return "[Date " + formattedDate + "]";
        } else {
            return formattedDate;
        }
    }

    /**
     * The local time zone adjustment is a value LocalTZA measured in milliseconds which when added
     * to UTC represents the local standard time. Daylight saving time is not reflected by LocalTZA.
     */
    public static long getLocalTZA(ZoneId localTimeZoneId) {
        ZoneOffset localTimeZoneOffset = localTimeZoneId.getRules().getOffset(Instant.ofEpochMilli(0));
        return localTimeZoneOffset.getTotalSeconds() * 1000L;
    }
}