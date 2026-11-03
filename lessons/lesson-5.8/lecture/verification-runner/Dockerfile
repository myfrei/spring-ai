FROM eclipse-temurin:25-jdk@sha256:83f4a7fc27df94dcc7b8de04d14cbbba8da14406f921e966969c3b09000aa507
WORKDIR /opt/verifier
COPY VerificationMain.java /opt/source/VerificationMain.java
COPY Probe.java /opt/probe/Probe.java
RUN javac --release 25 -d /opt/verifier /opt/source/VerificationMain.java
USER 65532:65532
ENTRYPOINT ["java","-XX:+DisableAttachMechanism","-Xmx192m","-Djava.io.tmpdir=/build","-cp","/opt/verifier","dev.course.verifier.VerificationMain"]
