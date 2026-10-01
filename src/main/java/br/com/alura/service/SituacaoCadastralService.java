package br.com.alura.service;

import br.com.alura.domain.Agencia;
import br.com.alura.domain.audit.Audit;
import br.com.alura.domain.saga.Saga;
import br.com.alura.domain.saga.SagaStatus;
import br.com.alura.repository.SituacaoCadastralRepository;
import br.com.alura.repository.saga.SagaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.MutinyEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

import java.time.LocalDateTime;

@ApplicationScoped
public class SituacaoCadastralService {

    private final SituacaoCadastralRepository situacaoCadastralRepository;
    private final Emitter<Audit> emitter;

    private final MutinyEmitter<br.com.alura.Agencia> mutinyEmitter;

    private final SagaRepository sagaRepository;

    private final ObjectMapper objectMapper;

    public SituacaoCadastralService(SituacaoCadastralRepository situacaoCadastralRepository,
                                    @Channel("notificacoes")Emitter<Audit> emitter,
                                    @Channel("remover-agencia-channel") MutinyEmitter<br.com.alura.Agencia> mutinyEmitter, SagaRepository sagaRepository, ObjectMapper objectMapper) {

        this.situacaoCadastralRepository = situacaoCadastralRepository;
        this.emitter = emitter;
        this.mutinyEmitter = mutinyEmitter;
        this.sagaRepository = sagaRepository;
        this.objectMapper = objectMapper;
    }

    @WithTransaction
    public Uni<Void> alterar(Agencia agencia) {
        return situacaoCadastralRepository
                .update("situacaoCadastral = ?1 where cnpj = ?2",
                        agencia.getSituacaoCadastral(), agencia.getCnpj())
                .onItem().invoke(() -> {
                    emitter.send(new Audit(agencia.getId(), agencia.getCnpj(), agencia.getSituacaoCadastral()));
                })
                .call(() -> {
                    try {
                        if(agencia.getSituacaoCadastral().equals("INATIVO")){
                            return sagaRepository.persist(new Saga(
                                    agencia.getCnpj(),
                                    objectMapper.writeValueAsString(agencia),
                                    SagaStatus.OPEN,
                                    LocalDateTime.now()

                            )).call( () -> {
                                br.com.alura.Agencia agenciaAvro = new br.com.alura.Agencia(
                                        agencia.getNome(),
                                        agencia.getRazaoSocial(),
                                        agencia.getCnpj(),
                                        agencia.getSituacaoCadastral());

                               return mutinyEmitter.send(agenciaAvro);
                            });
                        }
                        return Uni.createFrom().voidItem();
                    }catch (Exception e) {
                        return Uni.createFrom().failure(e);
                    }
                })
                .replaceWithVoid();
    }
}