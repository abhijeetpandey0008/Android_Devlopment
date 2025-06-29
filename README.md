# Android_Devlopment

Attendance tracking is essential in every learning and working environment—from schools 
and colleges to corporate offices—but traditional methods (manual roll calls, paper logs, or 
dedicated hardware readers) remain costly, labor-intensive, and error-prone. Inspired by the 
fact that virtually everyone carries an Android smartphone these days, I designed an app that 
requires no extra hardware: it uses the phone’s built-in Wi-Fi Direct to form peer-to-peer 
networks and exchange attendance records securely and instantly. With nothing more than a 
simple app installation, any classroom or workplace can deploy a fully decentralized, cost- 
effective attendance system that streamlines record-keeping and keeps all data local to 
participants’ devices. 
This r implementation, and evaluation of an Android-based 
decentralized attendance management system utilizing Wi-Fi Direct technology. The system 
integrates secure Google Sign-In authentication to verify users, dynamically establishes peer- 
to-peer connections without relying on traditional access points, and transfers serialized 
attendance records via TCP sockets. Key contributions include a lightweight data model for 
efficient network transmission, a responsive RecyclerView-based UI for real-time attendance 
tracking, and robust connection handling to minimize setup time and packet loss. Functional 
testing in typical indoor environments demonstrated an average group formation time of under 
5 seconds within a 10 m range and negligible data loss under 802.11n conditions. 
Multi-threaded socket programming ensures non-blocking UI by delegating network I/O to 
background threads, while a BroadcastReceiver manages Wi-Fi P2P peer discovery and 
connection events across activity restarts. The Android manifest includes necessary 
permissions—ACCESS_FINE_LOCATION, CHANGE_WIFI_STATE, and INTERNET— 
and declares the P2P service. Flow diagrams (Annexure I) map each module’s sequence: host 
group creation, service advertisement, client discovery, and bidirectional communication. The 
modular codebase—annotated with official Android Developers documentation references— 
facilitates future enhancements such as encrypted data transfer, persistent backend integration, 
automated reconnection logic, and UI theming for accessibility. 
Keywords: Android, Wi-Fi Direct, Peer-to-Peer Networking, TCP Sockets, Google Sign-In, 
Attendance Management, RecyclerView, BroadcastReceiver, Multithreading, Socket 
Programming
