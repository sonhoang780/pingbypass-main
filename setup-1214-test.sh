#!/usr/bin/env bash
set -Eeuo pipefail

# Clone the original 1.21.4 pingbypass repo and build its jar.
cd ~
rm -rf pingbypass-1214
git clone https://github.com/godmoduleu/pingbypass.git pingbypass-1214
cd pingbypass-1214
chmod +x gradlew

# Host has no JDK -- build inside a throwaway Gradle/JDK container instead of
# installing Java on the VPS. Mount the repo in and run gradlew as normal.
sudo docker run --rm -v "$PWD":/work -w /work gradle:8-jdk21 ./gradlew build -x test

JAR_PATH=$(find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' | head -1)
if [[ -z "$JAR_PATH" ]]; then echo "Build produced no jar, aborting."; exit 1; fi
echo "Built: $JAR_PATH"

mkdir -p docker-1214
cat > docker-1214/Dockerfile <<'EOF2'
FROM 3arthqu4ke/headlessmc:latest
WORKDIR /headlessmc
USER root
RUN set -eux; \
    if command -v apt-get >/dev/null 2>&1; then \
      apt-get update; \
      apt-get install -y --no-install-recommends ca-certificates curl unzip libopenal1 libflite1; \
      rm -rf /var/lib/apt/lists/*; \
    fi
RUN mkdir -p /root/.minecraft/mods /root/.minecraft/euclient /mc-stash /headlessmc/HeadlessMC && \
    printf 'hmc.always.lwjgl.flag=true\nhmc.assets.dummy=true\nhmc.mcdir=/root/.minecraft\nhmc.gamedir=/root/.minecraft\nhmc.jline.enabled=false\nhmc.commandline=false\n' > /headlessmc/config.properties
RUN echo 'precedence ::ffff:0:0/96 100' >> /etc/gai.conf
RUN hmc download 1.21.4 && hmc fabric 1.21.4
RUN curl -fsSL -o /root/.minecraft/mods/fabric-api.jar "https://github.com/FabricMC/fabric-api/releases/download/0.114.0+1.21.4/fabric-api-0.114.0+1.21.4.jar"
COPY astera-1214.jar /root/.minecraft/mods/astera.jar
COPY docker-1214/start.sh /start.sh
RUN cp /root/.minecraft/mods/astera.jar /mc-stash/astera.jar && \
    cp /root/.minecraft/mods/fabric-api.jar /mc-stash/fabric-api.jar && \
    chmod +x /start.sh
EXPOSE 25565
ENTRYPOINT ["/start.sh"]
EOF2

cat > docker-1214/start.sh <<'STARTSH'
#!/usr/bin/env bash
set -Eeuo pipefail
MC_DIR="/root/.minecraft"
PB_BIND_IP="${PB_BIND_IP:-0.0.0.0}"
PB_BIND_PORT="${PB_BIND_PORT:-25565}"
PB_PASSWORD="${PB_PASSWORD:-}"
JAVA_MEMORY="${JAVA_MEMORY:-2G}"
mkdir -p "$MC_DIR/mods" "$MC_DIR/euclient"
cp -f /mc-stash/astera.jar "$MC_DIR/mods/astera.jar"
cp -f /mc-stash/fabric-api.jar "$MC_DIR/mods/fabric-api.jar"
cat > "$MC_DIR/euclient/pingbypass.properties" <<EOPB
pb.server=true
pb.ip=${PB_BIND_IP}
pb.port=${PB_BIND_PORT}
pb.password=${PB_PASSWORD}
EOPB
JVM_ARGS="-Xmx${JAVA_MEMORY} -Xms${JAVA_MEMORY} -Djava.awt.headless=true -Dheadlessmc.lwjgl.stubs=true -Dhmc.jline.enabled=false"
JVM_ARGS="$JVM_ARGS -Dpb.server=true -Dpb.ip=${PB_BIND_IP} -Dpb.port=${PB_BIND_PORT} -Dpb.password=${PB_PASSWORD}"
# Let `docker run ... <image> login` (or any other hmc subcommand) through instead
# of always forcing `hmc launch` -- without this, the login step above can never
# actually run.
if [[ $# -gt 0 ]]; then exec hmc "$@"; fi
export HMC_JLINE_ENABLED=false
exec hmc launch fabric:1.21.4 -lwjgl -paulscode --jvm "$JVM_ARGS"
STARTSH

cp "$JAR_PATH" astera-1214.jar
sudo docker rm -f pingbypass-server-1214 >/dev/null 2>&1 || true
sudo docker rmi pingbypass-server-1214-img >/dev/null 2>&1 || true
sudo docker build --no-cache -t pingbypass-server-1214-img -f docker-1214/Dockerfile .

MC_DATA_DIR="$HOME/pb-mc-data-1214"
mkdir -p "$MC_DATA_DIR"

# Same as script.sh's setup_login() -- without this, HeadlessMC has no
# Minecraft account and launch fails with "You can't play the game without
# an account!" on every attempt. Interactive: opens a device-code login flow.
if [[ ! -f "$MC_DATA_DIR/accounts.json" ]] || ! grep -q refreshToken "$MC_DATA_DIR/accounts.json" 2>/dev/null; then
    echo "No saved Minecraft login found for the 1.21.4 test instance -- logging in now."
    sudo docker run --rm -it -v "$MC_DATA_DIR:/headlessmc/HeadlessMC" pingbypass-server-1214-img login
else
    echo "Saved Minecraft login found; skipping login."
fi

read -rsp "PB password for the 1.21.4 test instance: " PBPASS; echo
sudo docker run -d --name pingbypass-server-1214 --restart unless-stopped \
  -p 25566:25565 \
  -e "PB_PASSWORD=${PBPASS}" -e "PB_BIND_IP=0.0.0.0" -e "PB_BIND_PORT=25565" -e "JAVA_MEMORY=2G" \
  -v "$MC_DATA_DIR:/headlessmc/HeadlessMC" \
  pingbypass-server-1214-img

echo "Done. 1.21.4 test server listening on public port 25566 (26.1.2 one stays on 25565, untouched)."
