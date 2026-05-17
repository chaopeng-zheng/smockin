package com.smockin.admin.service;

import com.smockin.admin.exception.RecordNotFoundException;
import com.smockin.admin.exception.ValidationException;
import com.smockin.admin.persistence.dao.MQMockDAO;
import com.smockin.admin.persistence.dao.MQMockMessageDAO;
import com.smockin.admin.persistence.entity.MQMock;
import com.smockin.admin.persistence.entity.MQMockMessage;
import com.smockin.admin.persistence.entity.SmockinUser;
import com.smockin.admin.persistence.enums.MQTypeEnum;
import com.smockin.admin.persistence.enums.RecordStatusEnum;
import com.smockin.admin.persistence.enums.SmockinUserRoleEnum;
import com.smockin.admin.service.utils.UserTokenServiceUtils;
import com.smockin.mockserver.dto.MQMockDTO;
import com.smockin.mockserver.dto.MQMockMessageDTO;
import com.smockin.mockserver.dto.MockServerState;
import com.smockin.mockserver.engine.MockedMQServerEngine;
import com.smockin.utils.GeneralUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.*;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.*;

import static org.mockito.Mockito.*;

/**
 * Unit tests for MQMockServiceImpl
 */
@RunWith(MockitoJUnitRunner.class)
public class MQMockServiceImplTest {

    @Mock
    private UserTokenServiceUtils userTokenServiceUtils;

    @Mock
    private MQMockDAO mqMockDAO;

    @Mock
    private MQMockMessageDAO mqMockMessageDAO;

    @Mock
    private MockedMQServerEngine mockedMQServerEngine;

    @Spy
    @InjectMocks
    private MQMockServiceImpl mqMockService = new MQMockServiceImpl();

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    private String token;
    private SmockinUser smockinUser;
    private MQMock testMQMock;

    @Before
    public void setUp() {
        token = GeneralUtils.generateUUID();
        smockinUser = new SmockinUser();
        smockinUser.setRole(SmockinUserRoleEnum.ADMIN);
        smockinUser.setExtId("user-ext-id-001");

        when(userTokenServiceUtils.loadCurrentActiveUser(anyString())).thenReturn(smockinUser);

        // Create test MQ Mock
        testMQMock = new MQMock(
                "Test JMS MQ (IBM MQ)",
                MQTypeEnum.JMS,
                "TEST.QUEUE",
                false,
                RecordStatusEnum.ACTIVE,
                smockinUser,
                true
        );
        testMQMock.setExtId("mq-ext-id-001");
        testMQMock.setProperties(new HashMap<>(Map.of(
                "jmsProvider", "IBMMQ",
                "queueManager", "QM1",
                "channel", "SYSTEM.DEF.SVRCONN",
                "connectionName", "localhost(1414)"
        )));
    }

    @Test
    public void loadAll_Success_Test() throws RecordNotFoundException {
        // Setup
        List<MQMock> mqMocks = Arrays.asList(testMQMock);
        when(mqMockDAO.findAllByUser(smockinUser.getId())).thenReturn(mqMocks);
        when(mqMockMessageDAO.countByMqMockExtId(anyString())).thenReturn(5L);

        // Test
        List<MQMockDTO> result = mqMockService.loadAll(token);

        // Assertions
        Assert.assertNotNull(result);
        Assert.assertEquals(1, result.size());
        Assert.assertEquals("Test JMS MQ (IBM MQ)", result.get(0).getName());
        Assert.assertEquals("JMS", result.get(0).getMqType());
        Assert.assertEquals("TEST.QUEUE", result.get(0).getDestinationName());
        Assert.assertFalse(result.get(0).isTopic());
        Assert.assertEquals(5, result.get(0).getMessageCount());

        verify(mqMockDAO).findAllByUser(smockinUser.getId());
    }

    @Test
    public void loadById_Success_Test() throws RecordNotFoundException {
        // Setup
        when(mqMockDAO.findByExtIdAndUser("mq-ext-id-001", smockinUser.getId())).thenReturn(testMQMock);
        when(mqMockMessageDAO.countByMqMockExtId("mq-ext-id-001")).thenReturn(3L);

        // Test
        MQMockDTO result = mqMockService.loadById("mq-ext-id-001", token);

        // Assertions
        Assert.assertNotNull(result);
        Assert.assertEquals("Test JMS MQ (IBM MQ)", result.getName());
        Assert.assertEquals("mq-ext-id-001", result.getExtId());
        Assert.assertEquals("JMS", result.getMqType());

        verify(mqMockDAO).findByExtIdAndUser("mq-ext-id-001", smockinUser.getId());
    }

    @Test(expected = RecordNotFoundException.class)
    public void loadById_NotFound_Test() throws RecordNotFoundException {
        // Setup
        when(mqMockDAO.findByExtIdAndUser(anyString(), anyLong())).thenReturn(null);

        // Test
        mqMockService.loadById("non-existent-id", token);
    }

    @Test
    public void create_Success_Test() throws RecordNotFoundException, ValidationException {
        // Setup
        MQMockDTO dto = new MQMockDTO(
                null,
                "New JMS MQ (IBM MQ)",
                "JMS",
                "NEW.QUEUE",
                false,
                null,
                true,
                "ACTIVE",
                null
        );
        dto.setProperties(Map.of(
                "jmsProvider", "IBMMQ",
                "queueManager", "QM1",
                "channel", "SYSTEM.DEF.SVRCONN",
                "connectionName", "localhost(1414)"
        ));

        when(mqMockDAO.findByName("New JMS MQ (IBM MQ)")).thenReturn(null);
        when(mqMockDAO.save(any(MQMock.class))).thenAnswer(invocation -> {
            MQMock saved = invocation.getArgument(0);
            saved.setExtId("new-mq-ext-id");
            return saved;
        });

        // Test
        String extId = mqMockService.create(dto, token);

        // Assertions
        Assert.assertNotNull(extId);
        Assert.assertEquals("new-mq-ext-id", extId);

        verify(mqMockDAO).findByName("New JMS MQ (IBM MQ)");
        verify(mqMockDAO).save(any(MQMock.class));
    }

    @Test(expected = ValidationException.class)
    public void create_DuplicateName_Test() throws RecordNotFoundException, ValidationException {
        // Setup
        MQMockDTO dto = new MQMockDTO(
                null,
                "Existing MQ",
                "JMS",
                "TEST.QUEUE",
                false,
                null,
                true,
                "ACTIVE",
                null
        );

        when(mqMockDAO.findByName("Existing MQ")).thenReturn(testMQMock);

        // Test
        mqMockService.create(dto, token);
    }

    @Test(expected = ValidationException.class)
    public void create_MissingName_Test() throws RecordNotFoundException, ValidationException {
        // Setup
        MQMockDTO dto = new MQMockDTO();
        dto.setName(null);
        dto.setMqType("JMS");
        dto.setDestinationName("TEST.QUEUE");

        // Test
        mqMockService.create(dto, token);
    }

    @Test(expected = ValidationException.class)
    public void create_MissingDestination_Test() throws RecordNotFoundException, ValidationException {
        // Setup
        MQMockDTO dto = new MQMockDTO();
        dto.setName("Test MQ");
        dto.setMqType("JMS");
        dto.setDestinationName(null);

        // Test
        mqMockService.create(dto, token);
    }

    @Test
    public void update_Success_Test() throws RecordNotFoundException, ValidationException {
        // Setup
        when(mqMockDAO.findByExtIdAndUser("mq-ext-id-001", smockinUser.getId())).thenReturn(testMQMock);
        when(mqMockDAO.save(any(MQMock.class))).thenReturn(testMQMock);

        MQMockDTO dto = new MQMockDTO(
                "mq-ext-id-001",
                "Updated JMS MQ (IBM MQ)",
                "JMS",
                "UPDATED.QUEUE",
                true,
                null,
                true,
                "ACTIVE",
                null
        );

        // Test
        mqMockService.update("mq-ext-id-001", dto, token);

        // Assertions
        verify(mqMockDAO).save(any(MQMock.class));
        Assert.assertEquals("Updated JMS MQ (IBM MQ)", testMQMock.getName());
        Assert.assertEquals("UPDATED.QUEUE", testMQMock.getDestinationName());
        Assert.assertTrue(testMQMock.isTopic());
    }

    @Test
    public void delete_Success_Test() throws RecordNotFoundException {
        // Setup
        when(mqMockDAO.findByExtIdAndUser("mq-ext-id-001", smockinUser.getId())).thenReturn(testMQMock);

        // Test
        mqMockService.delete("mq-ext-id-001", token);

        // Assertions
        verify(mockedMQServerEngine).removeMQMock("mq-ext-id-001");
        verify(mqMockDAO).delete(testMQMock);
    }

    @Test
    public void getMQServerState_Success_Test() {
        // Setup
        MockServerState state = new MockServerState(true, 3);
        when(mockedMQServerEngine.getCurrentState()).thenReturn(state);

        // Test
        MockServerState result = mqMockService.getMQServerState();

        // Assertions
        Assert.assertNotNull(result);
        Assert.assertTrue(result.isRunning());
        Assert.assertEquals(3, result.getPort()); // Using port field to store active mocks count
    }

    @Test
    public void stopMQServer_Success_Test() throws RecordNotFoundException, ValidationException {
        // Test
        mqMockService.stopMQServer(token);

        // Assertions
        verify(mockedMQServerEngine).shutdown();
    }

    @Test
    public void loadMessages_Success_Test() throws RecordNotFoundException {
        // Setup
        when(mqMockDAO.findByExtIdAndUser("mq-ext-id-001", smockinUser.getId())).thenReturn(testMQMock);

        MQMockMessage msg1 = new MQMockMessage("msg-key-1", "Message Body 1", "text/plain", null, testMQMock);
        msg1.setExtId("msg-ext-id-001");

        MQMockMessage msg2 = new MQMockMessage("msg-key-2", "Message Body 2", "application/json", null, testMQMock);
        msg2.setExtId("msg-ext-id-002");

        when(mqMockMessageDAO.findByMqMockExtId("mq-ext-id-001")).thenReturn(Arrays.asList(msg1, msg2));

        // Test
        List<MQMockMessageDTO> result = mqMockService.loadMessages("mq-ext-id-001", token);

        // Assertions
        Assert.assertNotNull(result);
        Assert.assertEquals(2, result.size());
        Assert.assertEquals("msg-key-1", result.get(0).getMessageKey());
        Assert.assertEquals("Message Body 1", result.get(0).getMessageBody());
        Assert.assertEquals("msg-key-2", result.get(1).getMessageKey());
    }

    @Test
    public void sendMessage_Success_Test() throws RecordNotFoundException, ValidationException {
        // Setup
        when(mqMockDAO.findByExtIdAndUser("mq-ext-id-001", smockinUser.getId())).thenReturn(testMQMock);

        // Test
        mqMockService.sendMessage(
                "mq-ext-id-001",
                "Test Message Body",
                "test-key",
                "text/plain",
                null,
                token
        );

        // Assertions
        verify(mockedMQServerEngine).sendMessage(
                eq("mq-ext-id-001"),
                eq("Test Message Body"),
                eq("test-key"),
                eq("text/plain"),
                isNull()
        );
    }

    @Test(expected = ValidationException.class)
    public void sendMessage_EmptyBody_Test() throws RecordNotFoundException, ValidationException {
        // Setup
        when(mqMockDAO.findByExtIdAndUser("mq-ext-id-001", smockinUser.getId())).thenReturn(testMQMock);

        // Test
        mqMockService.sendMessage("mq-ext-id-001", "", "key", "text/plain", null, token);
    }

    @Test
    public void clearMessages_Success_Test() throws RecordNotFoundException {
        // Setup
        when(mqMockDAO.findByExtIdAndUser("mq-ext-id-001", smockinUser.getId())).thenReturn(testMQMock);

        // Test
        mqMockService.clearMessages("mq-ext-id-001", token);

        // Assertions
        verify(mqMockMessageDAO).deleteByMqMockExtId("mq-ext-id-001");
    }

    @Test
    public void validateMQMockDTO_Valid_Test() throws ValidationException {
        // Setup
        MQMockDTO dto = new MQMockDTO();
        dto.setName("Test MQ");
        dto.setMqType("IBM_MQ");
        dto.setDestinationName("TEST.QUEUE");

        // Test - should not throw exception
        mqMockService.validateMQMockDTO(dto);
    }

    @Test(expected = ValidationException.class)
    public void validateMQMockDTO_Null_Test() throws ValidationException {
        mqMockService.validateMQMockDTO(null);
    }

    @Test(expected = ValidationException.class)
    public void validateMQMockDTO_EmptyName_Test() throws ValidationException {
        MQMockDTO dto = new MQMockDTO();
        dto.setName("");
        dto.setMqType("IBM_MQ");
        dto.setDestinationName("TEST.QUEUE");

        mqMockService.validateMQMockDTO(dto);
    }

    @Test(expected = ValidationException.class)
    public void validateMQMockDTO_LongDestination_Test() throws ValidationException {
        MQMockDTO dto = new MQMockDTO();
        dto.setName("Test MQ");
        dto.setMqType("IBM_MQ");
        dto.setDestinationName("A".repeat(201));

        mqMockService.validateMQMockDTO(dto);
    }
}
