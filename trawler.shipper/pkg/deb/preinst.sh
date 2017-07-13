#!/bin/sh -e
# Create trawler user and group
USERNAME="trawler"
GROUPNAME="trawler"
getent group "$GROUPNAME" >/dev/null || groupadd -r "$GROUPNAME"
getent passwd "$USERNAME" >/dev/null || \
  useradd -r -g "$GROUPNAME" -G "adm" -d /usr/share/trawler -s /bin/false \
  -c "Trawler log monitoring system" "$USERNAME" 
exit 0
