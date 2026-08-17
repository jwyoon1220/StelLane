// 에셋 리소스 전용 모듈입니다.

// story/ 는 클래스패스 리소스가 아니라 run/story 로 직접 파일 복사되는 유저 편집 가능 데이터입니다
// (app/build.gradle.kts의 prepareRunEnv/deploy 참고) — jar에 중복으로 번들링하지 않도록 제외합니다.
sourceSets {
    main {
        resources {
            exclude("story/**")
        }
    }
}
