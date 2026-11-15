# R8 rules of the test APK in the minified instrumented run (-Ppiplayer.minifiedTests=true, TI §11.2). AGP shrinks the
# test APK of a minified build against the app's mapping, so its references to app classes follow their new names. The
# test code itself stays whole: the runner finds the tests by name and annotation.
-dontshrink
-dontoptimize
-keep class * { *; }
-keepattributes *

# Optional parts of the test libraries (AssertJ's JUnit 5 and XML helpers, Byte Buddy's agent and JNA paths, OkHttp's
# build annotations) that Android does not have and these tests never reach.
-dontwarn com.sun.jna.**
-dontwarn edu.umd.cs.findbugs.annotations.**
-dontwarn java.lang.instrument.**
-dontwarn java.lang.management.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn org.junit.jupiter.**
-dontwarn org.junit.platform.**
-dontwarn org.opentest4j.**
-dontwarn org.w3c.dom.bootstrap.**
