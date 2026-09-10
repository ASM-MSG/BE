package com.msg.fillmap.mission.seed;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import com.msg.fillmap.mission.service.MissionQueryService;
import com.msg.fillmap.mission.entity.MissionType;
import com.msg.fillmap.grid.dto.ViewportBounds;
import io.micrometer.core.instrument.MeterRegistry;

@SpringBootTest
class MissionWorkloadBenchmark {
	@Autowired FestivalMissionSeeder seeder;
	@Autowired MissionQueryService query;
	@Autowired JdbcTemplate jdbc;
	@Autowired EntityManagerFactory emf;
	@Autowired MeterRegistry registry;
	@Autowired org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor schedules;

	void measure(String label, int n, Runnable action) {
		var stats=emf.unwrap(SessionFactory.class).getStatistics();
		stats.setStatisticsEnabled(true);
		long sql=stats.getPrepareStatementCount();
		var timer=registry.find("hikaricp.connections.usage").timer();
		double usage=timer==null?0:timer.totalTime(TimeUnit.MILLISECONDS);
		long count=timer==null?0:timer.count();
		long begin=System.nanoTime(); action.run();
		double ms=(System.nanoTime()-begin)/1e6;
		System.out.printf(Locale.ROOT,"BENCH mission,%s,n=%d,ms=%.3f,hibernate_prepares=%d,poolUsageMs=%.3f,poolCount=%d%n",label,n,ms,stats.getPrepareStatementCount()-sql,timer==null?-1:timer.totalTime(TimeUnit.MILLISECONDS)-usage,timer==null?-1:timer.count()-count);
	}

	@Test void measureRealSeederAndQueries() throws Exception {
		schedules.getScheduledTasks().forEach(task -> task.cancel(false));
		assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("fillmap_tx_bench_20260908");
		assertThat(jdbc.queryForObject("select count(*) from missions",Long.class)).isZero();
		Path path=Files.createTempFile("tx-festivals-", ".jsonl");
		ReflectionTestUtils.setField(seeder,"enabled",true);
		ReflectionTestUtils.setField(seeder,"jsonlPath",path.toString());
		try {
			for(int n:Arrays.stream(System.getenv().getOrDefault("BENCH_SIZES", "100,1000,3000").split(",")).mapToInt(Integer::parseInt).toArray()) {
				List<String> lines=new ArrayList<>();
				for(int i=0;i<n;i++) {
					double lat=36.0+(i/100)*0.01, lon=127.0+(i%100)*0.01;
					lines.add(String.format(Locale.ROOT,"{\"name\":\"TXBENCH-%d\",\"latitude\":%.5f,\"longitude\":%.5f,\"startDate\":\"2026-09-01\",\"endDate\":\"2027-12-31\"}",i,lat,lon));
				}
				Files.write(path,lines);
				measure("seed-insert",n,()->seeder.run(new DefaultApplicationArguments()));
				assertThat(jdbc.queryForObject("select count(*) from missions",Long.class)).isEqualTo((long)n);
				assertThat(jdbc.queryForObject("select count(*) from mission_grids",Long.class)).isEqualTo(81L*n);
				jdbc.execute("ANALYZE missions"); jdbc.execute("ANALYZE mission_grids");
				for(int r=0;r<3;r++) {
					measure("seed-idempotent-"+r,n,()->seeder.run(new DefaultApplicationArguments()));
					query.invalidateSnapshot();
					measure("query-cold-"+r,n,()->assertThat(query.getMissionsInViewport(new ViewportBounds(36,127,36.1,127.1),MissionType.EVENT)).isNotEmpty());
					measure("query-warm-"+r,n,()->assertThat(query.getMissionsInViewport(new ViewportBounds(36,127,36.1,127.1),MissionType.EVENT)).isNotEmpty());
				}
				jdbc.update("delete from missions where title like 'TXBENCH-%'");
			}
		} finally {
			ReflectionTestUtils.setField(seeder,"enabled",false);
			jdbc.update("delete from missions where title like 'TXBENCH-%'");
			Files.deleteIfExists(path);
		}
	}
}
