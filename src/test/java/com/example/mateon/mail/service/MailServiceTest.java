package com.example.mateon.mail.service;

import jakarta.mail.Message;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 인증 메일의 형태를 고정한다.
 *
 * <p>
 * 메일은 실패해도 서버 로그에만 남고 사용자에게는 "코드가 안 온다"로만 보인다. 그래서
 * 눈으로 확인하기 가장 어려운 계층이고, 여기서 잡아 두는 값어치가 크다.
 *
 * <p>
 * 특히 <b>멀티파트(평문 + HTML) 구성</b>은 스팸 점수를 낮추려고 일부러 만든 것이다.
 * {@code helper.setText(plain, html)} 를 {@code setText(html, true)} 로 "간단히" 바꾸면
 * 코드도 잘 돌고 메일도 발송되지만, 평문 대체본이 사라져 스팸함 유입이 늘어난다 — 며칠 뒤
 * 가입 전환율로만 드러나는 종류의 회귀다.
 *
 * <p>
 * 발신자 표시명({@code MateOn})과 Reply-To 도 같은 이유로 고정한다.
 */
class MailServiceTest {

    private static final String FROM = "noreply@mateon.app";

    private JavaMailSender mailSender;
    private MailService mailService;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        mailService = new MailService(mailSender);
        // @Value 필드는 손 조립 객체에 주입되지 않는다.
        ReflectionTestUtils.setField(mailService, "from", FROM);
        // Session 없이 만든 MimeMessage 로 충분하다 (실제 전송은 하지 않는다).
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
    }

    @Test
    @DisplayName("제목은 '[MateOn] 이메일 인증코드' 고정이다 (메일 필터 규칙이 이 문자열에 걸려 있다)")
    void subject() throws Exception {
        mailService.sendVerificationCode("student@univ.ac.kr", "123456");

        assertThat(sentMessage().getSubject()).isEqualTo("[MateOn] 이메일 인증코드");
    }

    @Test
    @DisplayName("수신자는 인자로 받은 주소 하나다")
    void recipient() throws Exception {
        mailService.sendVerificationCode("student@univ.ac.kr", "123456");

        assertThat(sentMessage().getRecipients(Message.RecipientType.TO))
          .extracting(Object::toString)
          .containsExactly("student@univ.ac.kr");
    }

    @Test
    @DisplayName("발신자와 Reply-To 모두 표시명 'MateOn' 을 단다")
    void senderNameAndReplyTo() throws Exception {
        mailService.sendVerificationCode("student@univ.ac.kr", "123456");

        MimeMessage message = sentMessage();
        InternetAddress from = (InternetAddress) message.getFrom()[0];
        InternetAddress replyTo = (InternetAddress) message.getReplyTo()[0];

        assertThat(from.getAddress()).isEqualTo(FROM);
        assertThat(from.getPersonal()).isEqualTo("MateOn");
        assertThat(replyTo.getAddress()).isEqualTo(FROM);
        assertThat(replyTo.getPersonal()).isEqualTo("MateOn");
    }

    @Test
    @DisplayName("평문과 HTML 두 파트를 모두 담고, 코드가 양쪽에 들어 있다 (평문 단독은 스팸 점수가 높다)")
    void multipartCarriesCodeInBothParts() throws Exception {
        mailService.sendVerificationCode("student@univ.ac.kr", "246810");

        MimeMessage message = sentMessage();
        assertThat(message.getContent()).isInstanceOf(MimeMultipart.class);

        String plain = textPart(message, "text/plain");
        String html = textPart(message, "text/html");

        // 인증코드는 평문/HTML 어느 쪽으로 열어도 보여야 한다.
        assertThat(plain).contains("246810");
        assertThat(html).contains("246810").contains("<div");
    }

    @Test
    @DisplayName("본문은 유효기간 5분을 안내한다 (서비스의 만료 설정과 같은 값)")
    void mentionsFiveMinuteValidity() throws Exception {
        mailService.sendVerificationCode("student@univ.ac.kr", "123456");

        MimeMessage message = sentMessage();
        assertThat(textPart(message, "text/plain")).contains("5분간 유효합니다");
        assertThat(textPart(message, "text/html")).contains("5분간");
    }

    @Test
    @DisplayName("전송 실패는 IllegalStateException 이 아니라 그대로 올라간다 (리스너가 삼킬 몫이다)")
    void sendFailurePropagates() {
        doThrow(new MailSendException("SMTP 연결 실패")).when(mailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> mailService.sendVerificationCode("student@univ.ac.kr", "123456"))
          .isInstanceOf(MailSendException.class);
    }

    @Test
    @DisplayName("메일 조립 실패는 IllegalStateException 으로 감싼다 (원인은 유지)")
    void buildFailureIsWrapped() {
        // 수신자 주소가 문법상 깨져 있으면 MessagingException 이 나고, 서비스가 이를 감싼다.
        assertThatThrownBy(() -> mailService.sendVerificationCode("not a valid address", "123456"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("인증 메일 생성에 실패했습니다.")
          .hasCauseInstanceOf(jakarta.mail.MessagingException.class);
    }

    // --- 헬퍼 ---------------------------------------------------------------
    private MimeMessage sentMessage() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }

    /**
     * 지정한 MIME 타입의 텍스트 파트를 <b>디코딩된 상태로</b> 꺼낸다.
     *
     * <p>
     * 원문(writeTo)을 통째로 읽어 문자열 검색을 하면 안 된다 — 한글이 섞인 파트는 base64 로
     * 인코딩되어 나가서, 그 안의 인증코드가 원문에는 보이지 않는다. 실제로 그렇게 짰다가
     * "코드가 한 번만 나온다"로 헛짚었다. 파트를 타고 들어가 getContent() 로 디코딩해야 한다.
     *
     * <p>
     * MimeMessageHelper(multipart=true) 는 mixed → related → alternative 로 겹겹이 감싸므로
     * 재귀로 훑는다.
     */
    private String textPart(MimeMessage message, String mimeType) throws Exception {
        // saveChanges() 를 부르지 않으면 각 파트의 Content-Type 헤더가 아직 실체화되지 않아
        // getContentType() 이 전부 기본값 "text/plain" 을 돌려준다. 그러면 JavaMail 이 중첩
        // 멀티파트를 텍스트로 해석해 버려서 구조 자체가 사라진다 (실제로 여기서 한 번 헛짚었다).
        message.saveChanges();

        Map<String, String> parts = new LinkedHashMap<>();
        collect(message, parts);

        String found = parts.entrySet().stream()
          .filter(entry -> entry.getKey().startsWith(mimeType))
          .map(Map.Entry::getValue)
          .findFirst()
          .orElse(null);

        assertThat(found)
          .as("%s 파트가 있어야 한다 (실제 파트: %s)", mimeType, parts.keySet())
          .isNotNull();
        return found;
    }

    /**
     * 중첩 멀티파트를 훑어 "content-type → 디코딩된 본문" 으로 모은다.
     */
    private void collect(Part part, Map<String, String> into) throws Exception {
        Object content = part.getContent();

        if (content instanceof MimeMultipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                collect(multipart.getBodyPart(i), into);
            }
        } else if (content instanceof String text) {
            into.putIfAbsent(part.getContentType().toLowerCase(), text);
        }
    }
}
