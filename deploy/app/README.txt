chmod +x /mydata/app/start-all.sh
/mydata/app/start-all.sh
chmod +x /mydata/app/stop-all.sh
/mydata/app/stop-all.sh

pkill -f gulimall-member-0.0.1-SNAPSHOT.jar
sleep 3
cd /mydata/app
nohup java -Xms256m -Xmx512m -jar gulimall-member-0.0.1-SNAPSHOT.jar > /mydata/logs/gulimall-member-0.0.1-SNAPSHOT.log 2>&1 &
