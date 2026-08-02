#!/bin/sh
sudo docker exec pingbypass-server sh -c "echo 'precedence ::ffff:0:0/96 100' >> /etc/gai.conf"
sudo docker restart pingbypass-server
