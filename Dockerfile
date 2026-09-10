# 앱 이미지 (MSG-589). jar 는 밖에서 만든다 — CI 가 gradle 캐시로 빌드하고 이 파일은 그 jar 를 담기만 한다.
#   ./gradlew bootJar -x test && docker build -t fillmap .
# dev(api)·인코딩 워커·prod 가 같은 이미지를 쓰고 프로파일·env 만 다르다.

# 1) layered jar 를 레이어별 디렉터리로 펼친다 — 의존성 레이어가 캐시돼 코드만 바뀐 빌드는 push 가 수 MB 다.
FROM eclipse-temurin:21-jre-noble AS extract
WORKDIR /w
COPY build/libs/fillmap-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# 2) 런타임. ffmpeg·ffprobe 는 정적 빌드 바이너리를 복사한다 — apt 의 ffmpeg 는 amd64 에서 의존 라이브러리까지
#    453MB 라 이미지가 1.37GB 였다(2026-09-10 실측). 정적 빌드는 두 바이너리 합쳐 ~100MB 이고 앱이 쓰는
#    libx264·aac·scale·faststart·mjpeg 썸네일을 전부 포함한다 (FfmpegRunner 의 인자 기준). 태그+digest 로 고정.
#    curl 은 compose healthcheck 용.
FROM eclipse-temurin:21-jre-noble
COPY --from=mwader/static-ffmpeg:7.1.1@sha256:11a44711684c0b9f754c047dcd64235b8b52deab251bd0e0a86f22faa160749c \
	/ffmpeg /ffprobe /usr/local/bin/
RUN apt-get update \
	&& apt-get install -y --no-install-recommends curl \
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
