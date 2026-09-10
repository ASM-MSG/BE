# 앱 이미지 (MSG-589). jar 는 밖에서 만든다 — CI 가 gradle 캐시로 빌드하고 이 파일은 그 jar 를 담기만 한다.
#   ./gradlew bootJar -x test && docker build -t fillmap .
# dev(api)·인코딩 워커·prod 가 같은 이미지를 쓰고 프로파일·env 만 다르다.

# 1) layered jar 를 레이어별 디렉터리로 펼친다 — 의존성 레이어가 캐시돼 코드만 바뀐 빌드는 push 가 수 MB 다.
FROM eclipse-temurin:21-jre-noble AS extract
WORKDIR /w
COPY build/libs/fillmap-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# 2) 런타임. ffmpeg 는 인코딩·선분석 probe 의 런타임 의존(전에는 CD 가 호스트에 apt 로 깔았다).
#    curl 은 compose healthcheck 용.
FROM eclipse-temurin:21-jre-noble
RUN apt-get update \
	&& apt-get install -y --no-install-recommends ffmpeg curl \
	&& rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=extract /w/extracted/dependencies/ ./
COPY --from=extract /w/extracted/spring-boot-loader/ ./
COPY --from=extract /w/extracted/snapshot-dependencies/ ./
COPY --from=extract /w/extracted/application/ ./
# 비root. uid 1000 = 호스트 ubuntu — 바인드 마운트한 pem·FCM json(600) 을 그대로 읽는다.
USER 1000:1000
ENV TZ=UTC
EXPOSE 8080 8081
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
