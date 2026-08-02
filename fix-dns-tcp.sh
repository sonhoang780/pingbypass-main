#!/bin/sh
sudo docker exec pingbypass-server sed -i "s/-Djava.net.preferIPv4Stack=true\"/-Djava.net.preferIPv4Stack=true -Dio.netty.resolver.dns.forceTcp=true\"/" /start.sh
sudo docker restart pingbypass-server
