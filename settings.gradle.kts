// 플러그인 포털의 아티팩트 호스트가 샌드박스에서 막혀 있어 Central을 앞세운다(Kotlin·Boot 마커는 Central에 있다).
pluginManagement {
	repositories {
		mavenCentral()
		gradlePluginPortal()
	}
}

rootProject.name = "equip-recipe-rollout"

include("backend", "console-mock")
