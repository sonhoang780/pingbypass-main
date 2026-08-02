#!/bin/sh
sudo docker exec pingbypass-server sed -i "s/-Dhmc.jline.enabled=false\"/-Dhmc.jline.enabled=false -Djava.net.preferIPv4Stack=true\"/" /start.sh
sudo docker restart pingbypass-server
