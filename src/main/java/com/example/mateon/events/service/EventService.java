package com.example.mateon.events.service;

import com.example.mateon.events.dto.EventRequestDTO;
import com.example.mateon.events.dto.EventResponseDTO;
import com.example.mateon.events.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;

    /**
     * 활동(공모전 등) 등록.
     * 중복 검사는 하지 않는다 — external_id 의 UNIQUE 제약을 V18 에서 해제했고,
     * 같은 활동을 두 번 올리는 것은 등록자가 판단할 문제다.
     * embeddingVector 는 채우지 않는다. 추천 점수(EventMatchingService)는 키워드/전공/캠퍼스
     * 문자열 매칭만 쓰므로 비어 있어도 정상 동작한다.
     */
    @Transactional
    public EventResponseDTO createEvent(EventRequestDTO request) {
        return new EventResponseDTO(eventRepository.save(request.toEntity()));
    }
}
