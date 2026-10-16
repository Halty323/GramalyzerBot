package com.halty.gramalyzerbot.post;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "discussion_roots")
@Getter
@Setter
@NoArgsConstructor
public class DiscussionRoot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_chat_id", nullable = false)
    private Long groupChatId;

    @Column(name = "group_message_id", nullable = false)
    private Integer groupMessageId;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "post_message_id", nullable = false)
    private Integer postMessageId;
}
