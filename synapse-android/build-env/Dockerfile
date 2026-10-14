# Ambiente de build reproduzível para qualquer máquina com Docker e acesso à internet (Maven/Google).
FROM eclipse-temurin:17-jdk
ENV ANDROID_HOME=/opt/android-sdk
RUN apt-get update && apt-get install -y --no-install-recommends unzip curl git && rm -rf /var/lib/apt/lists/*
RUN mkdir -p $ANDROID_HOME/cmdline-tools && cd /tmp \
 && curl -fsSL -o cmd.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
 && unzip -q cmd.zip -d $ANDROID_HOME/cmdline-tools && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest && rm cmd.zip
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:/opt/gradle/bin
RUN yes | sdkmanager --licenses >/dev/null && sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
RUN curl -fsSL -o /tmp/g.zip https://services.gradle.org/distributions/gradle-8.7-bin.zip && unzip -q /tmp/g.zip -d /opt && mv /opt/gradle-8.7 /opt/gradle && rm /tmp/g.zip
WORKDIR /work
CMD ["bash","-lc","gradle :app:testDebugUnitTest :app:assembleDebug --no-daemon"]
