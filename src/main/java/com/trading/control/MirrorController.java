package com.trading.control;

import com.trading.mirror.MirrorPublisher;
import com.trading.mirror.MirrorSnapshot;
import com.trading.mirror.MirrorSnapshotAssembler;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 미러 연결 확인용 수동 전송 창구 (localhost 전용 — application-paper.yml의 server.address).
 *
 * 정기 전송은 거래일 장중에만 돌기 때문에, 설정을 처음 넣고 맞는지 보려면 장이 열릴 때까지
 * 기다려야 한다. 이 창구는 그 확인을 위해 시간 제한 없이 한 번 보낸다.
 * 매매 상태는 바꾸지 않는다 — 읽어서 복사할 뿐이다.
 */
@RestController
@RequestMapping("/api/trading")
@Profile("paper")
public class MirrorController {

    private final MirrorSnapshotAssembler assembler;
    private final MirrorPublisher publisher;

    public MirrorController(MirrorSnapshotAssembler assembler, MirrorPublisher publisher) {
        this.assembler = assembler;
        this.publisher = publisher;
    }

    @PostMapping("/mirror-push")
    public Map<String, Object> push() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            MirrorSnapshot snapshot = assembler.assemble();
            boolean sent = publisher.publish(snapshot);
            result.put("success", sent);
            result.put("message", sent
                    ? "Supabase로 지금 상태를 한 번 보냈습니다"
                    : "보내지 않았습니다 — SUPABASE_URL·SUPABASE_KEY 설정과 서버 로그를 확인하세요");
            result.put("holdings",  snapshot.holdings().size());
            result.put("updatedAt", snapshot.updatedAt());
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "스냅샷을 만들지 못했습니다: " + e.getMessage());
        }
        return result;
    }
}
